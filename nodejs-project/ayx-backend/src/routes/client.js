// Public + installation-authenticated client endpoints:
// health, bootstrap, incremental sync, announcements, statuses, media.

import { createReadStream, promises as fs } from 'node:fs';
import path from 'node:path';
import { Router } from 'express';
import { z } from 'zod';
import { get, all, run, insertRow, transaction, nowIso } from '../db.js';
import { config } from '../config.js';
import { installationAuth } from '../auth-installation.js';
import {
  validate,
  paginationSchema,
  paginated,
} from '../middleware/validate.js';
import {
  maxEventId,
  serializeAnnouncement,
  serializeStatus,
} from '../services/sync.js';
import { uploadDir } from '../services/media.js';

const router = Router();

// ---------------------------------------------------------------- health (PUBLIC, no auth)

router.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'ayx-group-api',
    time: new Date().toISOString(),
  });
});

// ---------------------------------------------------------------- bootstrap

// GET /v1/group/bootstrap — full current state for a fresh client.
router.get('/group/bootstrap', installationAuth, async (req, res, next) => {
  try {
    const now = new Date();
    const replyCutoff = req.installation.lastReplySeenAt ?? new Date(0);
    const announcements = all(
      'announcements',
      `SELECT * FROM announcements WHERE status = ? ORDER BY published_at DESC`,
      ['published']
    );
    const statuses = all(
      'statuses',
      `SELECT * FROM statuses WHERE status = ? AND expires_at > ? ORDER BY created_at DESC`,
      ['published', now]
    );
    const unreadReplyCount = get(
      null,
      `SELECT COUNT(*) AS c FROM admin_replies r
         JOIN user_messages m ON m.id = r.message_id
        WHERE m.installation_id = ? AND r.created_at > ?`,
      [req.installation.id, replyCutoff]
    ).c;
    const cursor = await maxEventId();

    const stamp = nowIso();
    await transaction(() => {
      run(
        'UPDATE installations SET last_sync_at = ?, last_reply_seen_at = ?, updated_at = ? WHERE id = ?',
        [stamp, stamp, stamp, req.installation.id]
      );
      run(
        `INSERT INTO device_cursors (installation_id, last_event_id, updated_at)
         VALUES (?, ?, ?)
         ON CONFLICT(installation_id) DO UPDATE
           SET last_event_id = excluded.last_event_id, updated_at = excluded.updated_at`,
        [req.installation.id, cursor, stamp]
      );
    });

    res.json({
      server_time: now.toISOString(),
      cursor: cursor.toString(),
      announcements: announcements.map(serializeAnnouncement),
      statuses: statuses.map((s) => serializeStatus(s, config.apiPublicBase)),
      unread_reply_count: unreadReplyCount,
    });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- sync

const syncQuery = z.object({
  cursor: z.coerce
    .number()
    .int()
    .min(0)
    .default(0),
});

// GET /v1/sync?cursor= — incremental sync. Never depends on FCM; a client
// that missed every push notification converges here.
router.get('/sync', installationAuth, validate(syncQuery, 'query'), async (req, res, next) => {
  try {
    const me = req.installation.id;
    const from = Number(req.query.cursor);
    const LIMIT = 500;

    const events = all(
      'sync_events',
      `SELECT * FROM sync_events
        WHERE id > ? AND (installation_id IS NULL OR installation_id = ?)
        ORDER BY id ASC LIMIT ?`,
      [from, me, LIMIT]
    );

    const now = new Date();
    const annUpsert = [];
    const annDeleted = new Set();
    const stUpsert = [];
    const stDeleted = new Set();
    const msgUpsert = [];
    const msgDeleted = new Set();

    for (const ev of events) {
      switch (ev.kind) {
        case 'announcement.upsert': {
          const a = get('announcements', 'SELECT * FROM announcements WHERE id = ?', [ev.entityId]);
          if (a && a.status === 'published') annUpsert.push(serializeAnnouncement(a));
          else annDeleted.add(ev.entityId);
          break;
        }
        case 'announcement.deleted':
          annDeleted.add(ev.entityId);
          break;
        case 'status.upsert': {
          const s = get('statuses', 'SELECT * FROM statuses WHERE id = ?', [ev.entityId]);
          if (s && s.status === 'published' && s.expiresAt > now) stUpsert.push(serializeStatus(s, config.apiPublicBase));
          else stDeleted.add(ev.entityId);
          break;
        }
        case 'status.deleted':
          stDeleted.add(ev.entityId);
          break;
        case 'message.upsert': {
          // Private event — the query already scoped installationId = me.
          const m = get(
            'user_messages',
            'SELECT * FROM user_messages WHERE id = ? AND installation_id = ?',
            [ev.entityId, me]
          );
          if (m) {
            const replies = all(
              'admin_replies',
              'SELECT * FROM admin_replies WHERE message_id = ? ORDER BY created_at ASC',
              [m.id]
            );
            msgUpsert.push({
              id: m.id,
              body: m.body,
              kind: m.kind,
              status: m.status,
              created_at: m.createdAt,
              replies: replies.map((r) => ({ id: r.id, body: r.body, created_at: r.createdAt })),
            });
          }
          break;
        }
        case 'message.deleted':
          msgDeleted.add(ev.entityId);
          break;
        default:
          break;
      }
    }

    const newCursor = events.length > 0 ? events[events.length - 1].id : from;

    // Deletes win: if an entity was upserted and then deleted within the
    // same batch, only the delete is reported (self-consistent response).
    const annUpsertFinal = annUpsert.filter((a) => !annDeleted.has(a.id));
    const stUpsertFinal = stUpsert.filter((s) => !stDeleted.has(s.id));
    const msgUpsertFinal = msgUpsert.filter((m) => !msgDeleted.has(m.id));

    const stamp = nowIso();
    await transaction(() => {
      run('UPDATE installations SET last_sync_at = ?, updated_at = ? WHERE id = ?', [
        stamp,
        stamp,
        me,
      ]);
      run(
        `INSERT INTO device_cursors (installation_id, last_event_id, updated_at)
         VALUES (?, ?, ?)
         ON CONFLICT(installation_id) DO UPDATE
           SET last_event_id = excluded.last_event_id, updated_at = excluded.updated_at`,
        [me, newCursor, stamp]
      );
    });

    res.json({
      cursor: newCursor.toString(),
      announcements: { upsert: annUpsertFinal, deleted: [...annDeleted] },
      statuses: { upsert: stUpsertFinal, deleted: [...stDeleted] },
      messages: { upsert: msgUpsertFinal, deleted: [...msgDeleted] },
    });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- content lists

router.get('/announcements', installationAuth, validate(paginationSchema, 'query'), async (req, res, next) => {
  try {
    const { page, per_page } = req.query;
    const total = get(null, `SELECT COUNT(*) AS c FROM announcements WHERE status = ?`, ['published']).c;
    const rows = all(
      'announcements',
      `SELECT * FROM announcements WHERE status = ? ORDER BY published_at DESC LIMIT ? OFFSET ?`,
      ['published', per_page, (page - 1) * per_page]
    );
    res.json(paginated(rows.map(serializeAnnouncement), total, page, per_page));
  } catch (err) {
    next(err);
  }
});

router.get('/statuses', installationAuth, async (req, res, next) => {
  try {
    const rows = all(
      'statuses',
      `SELECT * FROM statuses WHERE status = ? AND expires_at > ? ORDER BY created_at DESC`,
      ['published', new Date()]
    );
    res.json({ data: rows.map((s) => serializeStatus(s, config.apiPublicBase)) });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- media streaming

// GET /v1/media/{id} — installation auth REQUIRED. Only streams media linked
// to a currently visible (non-expired) status. `Cache-Control: private`.
router.get('/media/:id', installationAuth, async (req, res, next) => {
  try {
    const asset = get('media_assets', 'SELECT * FROM media_assets WHERE id = ?', [req.params.id]);
    if (!asset) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Media not found.' } });
    }
    const visible = get(
      null,
      `SELECT id FROM statuses WHERE media_id = ? AND status = ? AND expires_at > ? LIMIT 1`,
      [asset.id, 'published', new Date()]
    );
    if (!visible) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Media not found.' } });
    }
    const filePath = path.join(uploadDir(), asset.filename);
    try {
      await fs.access(filePath);
    } catch {
      return res.status(404).json({ error: { code: 'not_found', message: 'Media not found.' } });
    }
    res.setHeader('Content-Type', asset.mime);
    res.setHeader('Content-Length', asset.sizeBytes);
    res.setHeader('Cache-Control', 'private, max-age=86400');
    res.setHeader('Content-Disposition', `inline; filename="${asset.filename}"`);
    createReadStream(filePath).pipe(res);
  } catch (err) {
    next(err);
  }
});

export default router;
