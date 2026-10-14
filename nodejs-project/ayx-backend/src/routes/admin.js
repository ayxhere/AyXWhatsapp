// Admin API. EVERY route here is behind `adminAuth` (server-side session
// lookup + CSRF for mutating methods). Mounted at /v1/admin in app.js with
// the middleware applied at mount time — no route is reachable without it.

import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import { rateLimit } from 'express-rate-limit';
import multer from 'multer';
import { z } from 'zod';
import bcrypt from 'bcryptjs';
import { authenticator } from 'otplib';
import { get, all, run, insertRow, updateById, deleteById, transaction, likeEscape, newId, nowIso } from '../db.js';
import { config } from '../config.js';
import { validate, paginationSchema, paginated } from '../middleware/validate.js';
import { clientIp, truncateIp } from '../middleware/ip.js';
import { auditLog } from '../services/audit.js';
import {
  emitEvent,
  serializeAnnouncement,
  serializeStatus,
} from '../services/sync.js';
import { createPushJob } from '../services/push.js';
import {
  ALLOWED_MIME,
  ingestUpload,
  deleteAssetFile,
  tmpDir,
  ensureUploadDir,
} from '../services/media.js';

const router = Router();

const audited = (req) => ({
  adminUserId: req.admin.id,
  actor: req.admin.username,
  ip: truncateIp(clientIp(req)),
});

const uploadLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 60,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  keyGenerator: (req) => `admin:${req.admin?.id ?? clientIp(req)}`,
  handler: (req, res) =>
    res.status(429).json({
      error: { code: 'rate_limited', message: 'Upload rate limit exceeded.' },
    }),
});

// ---------------------------------------------------------------- dashboard

// GET /v1/admin/dashboard — all values from REAL database queries.
router.get('/dashboard', async (req, res, next) => {
  try {
    const now = new Date();
    const dayAgo = new Date(now.getTime() - 24 * 60 * 60 * 1000);
    const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
    const fiveMinAgo = new Date(now.getTime() - 5 * 60 * 1000);

    const cnt = (table, where, params) =>
      get(null, `SELECT COUNT(*) AS c FROM ${table} WHERE ${where}`, params).c;

    const [
      installationsTotal,
      installationsActive7d,
      installationsOnline,
      announcementsPublished,
      statusesActive,
      messagesPending,
      messagesUnread,
      pushFailures24h,
      recentActivity,
    ] = await Promise.all([
      cnt('installations', '1 = 1', []),
      cnt('installations', 'revoked = 0 AND last_sync_at > ?', [weekAgo]),
      // "Online" = checked in (heartbeat or any authed call... currently
      // heartbeat/sync) within the last 5 minutes. Best-effort presence,
      // not a guarantee the app is in the foreground.
      cnt('installations', 'revoked = 0 AND blocked = 0 AND last_seen_at > ?', [fiveMinAgo]),
      cnt('announcements', 'status = ?', ['published']),
      cnt('statuses', 'status = ? AND expires_at > ?', ['published', now]),
      cnt('user_messages', 'status = ?', ['pending']),
      // Unread = never opened by an admin, not archived, not spam.
      cnt('user_messages', 'read_at IS NULL AND archived = 0 AND status != ?', ['spam']),
      cnt('announcement_receipts', 'status = ? AND created_at > ?', ['failed', dayAgo]),
      all(
        'audit_logs',
        'SELECT id, actor, action, target, result, ip_trunc, created_at FROM audit_logs ORDER BY id DESC LIMIT 10'
      ),
    ]);

    res.json({
      installations_total: installationsTotal,
      installations_active_7d: installationsActive7d,
      installations_online: installationsOnline,
      announcements_published: announcementsPublished,
      statuses_active: statusesActive,
      messages_pending: messagesPending,
      messages_unread: messagesUnread,
      push_failures_24h: pushFailures24h,
      recent_activity: recentActivity.map((a) => ({
        id: a.id.toString(),
        actor: a.actor,
        action: a.action,
        target: a.target,
        result: a.result,
        ip: a.ipTrunc,
        created_at: a.createdAt,
      })),
    });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- announcements

const announcementListQuery = paginationSchema.extend({
  status: z.enum(['draft', 'scheduled', 'published', 'archived']).optional(),
});

const announcementCreateSchema = z.object({
  title: z.string().min(1).max(200),
  body: z.string().min(1).max(20000),
  scheduled_at: z.string().datetime({ offset: true }).optional(),
  notify: z.boolean().optional(),
  push_title: z.string().min(1).max(120).optional(),
  push_body: z.string().min(1).max(240).optional(),
});

const announcementPatchSchema = z.object({
  title: z.string().min(1).max(200).optional(),
  body: z.string().min(1).max(20000).optional(),
  scheduled_at: z.string().datetime({ offset: true }).nullable().optional(),
});

const publishSchema = z.object({
  notify: z.boolean().optional(),
  push_title: z.string().min(1).max(120).optional(),
  push_body: z.string().min(1).max(240).optional(),
});

router.get('/announcements', validate(announcementListQuery, 'query'), async (req, res, next) => {
  try {
    const { page, per_page, status } = req.query;
    const where = status ? 'status = ?' : '1 = 1';
    const params = status ? [status] : [];
    const total = get(null, `SELECT COUNT(*) AS c FROM announcements WHERE ${where}`, params).c;
    const rows = all(
      'announcements',
      `SELECT * FROM announcements WHERE ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`,
      [...params, per_page, (page - 1) * per_page]
    );
    res.json(paginated(rows.map(serializeAnnouncement), total, page, per_page));
  } catch (err) {
    next(err);
  }
});

router.post('/announcements', validate(announcementCreateSchema), async (req, res, next) => {
  try {
    const { title, body, scheduled_at, notify, push_title, push_body } = req.body;
    let status = 'draft';
    let scheduledAt = null;
    if (scheduled_at) {
      scheduledAt = new Date(scheduled_at);
      if (scheduledAt > new Date()) status = 'scheduled';
    }
    const id = newId();
    const now = nowIso();
    insertRow('announcements', {
      id,
      title,
      body,
      status,
      scheduled_at: scheduledAt,
      notify_pending: status === 'scheduled' ? !!notify : false,
      push_title: push_title ?? null,
      push_body: push_body ?? null,
      created_at: now,
      updated_at: now,
    });
    const a = get('announcements', 'SELECT * FROM announcements WHERE id = ?', [id]);
    await auditLog({ ...audited(req), action: 'announcement.create', target: a.id, result: 'success' });
    res.status(201).json(serializeAnnouncement(a));
  } catch (err) {
    next(err);
  }
});

async function loadAnnouncement(req, res) {
  const a = get('announcements', 'SELECT * FROM announcements WHERE id = ?', [req.params.id]);
  if (!a) {
    res.status(404).json({ error: { code: 'not_found', message: 'Announcement not found.' } });
    return null;
  }
  return a;
}

router.patch('/announcements/:id', validate(announcementPatchSchema), async (req, res, next) => {
  try {
    const a = await loadAnnouncement(req, res);
    if (!a) return;
    const data = { updated_at: nowIso() };
    if (req.body.title !== undefined) data.title = req.body.title;
    if (req.body.body !== undefined) data.body = req.body.body;
    if (req.body.scheduled_at !== undefined) {
      data.scheduled_at = req.body.scheduled_at ? new Date(req.body.scheduled_at) : null;
    }
    const updated = updateById('announcements', a.id, data);
    if (updated.status === 'published') {
      await emitEvent({ kind: 'announcement.upsert', entity: 'announcement', entityId: a.id });
    }
    await auditLog({ ...audited(req), action: 'announcement.update', target: a.id, result: 'success' });
    res.json(serializeAnnouncement(updated));
  } catch (err) {
    next(err);
  }
});

router.post('/announcements/:id/publish', validate(publishSchema), async (req, res, next) => {
  try {
    const a = await loadAnnouncement(req, res);
    if (!a) return;

    let updated = a;
    if (a.status !== 'published') {
      updated = updateById('announcements', a.id, {
        status: 'published',
        published_at: nowIso(),
        notify_pending: false,
        updated_at: nowIso(),
      });
      await emitEvent({ kind: 'announcement.upsert', entity: 'announcement', entityId: a.id });
    }

    let pushJob = null;
    if (req.body.notify) {
      pushJob = await createPushJob({
        announcementId: a.id,
        title: req.body.push_title || updated.title,
        body: req.body.push_body || updated.body.slice(0, 240),
        deepLink: 'ayxgroup://announcements',
        createdBy: req.admin.id,
      });
    }

    await auditLog({
      ...audited(req),
      action: 'announcement.publish',
      target: a.id,
      result: 'success',
      meta: { notify: !!req.body.notify, push_job: pushJob?.id ?? null, push_status: pushJob?.status ?? null },
    });

    res.json({
      announcement: serializeAnnouncement(updated),
      push_job: pushJob
        ? {
            id: pushJob.id,
            status: pushJob.status,
            total_targets: pushJob.totalTargets,
            sent: pushJob.sentCount,
            failed: pushJob.failedCount,
            skipped: pushJob.skippedCount,
          }
        : null,
    });
  } catch (err) {
    next(err);
  }
});

router.post('/announcements/:id/archive', async (req, res, next) => {
  try {
    const a = await loadAnnouncement(req, res);
    if (!a) return;
    const updated = updateById('announcements', a.id, { status: 'archived', updated_at: nowIso() });
    await emitEvent({ kind: 'announcement.deleted', entity: 'announcement', entityId: a.id });
    await auditLog({ ...audited(req), action: 'announcement.archive', target: a.id, result: 'success' });
    res.json(serializeAnnouncement(updated));
  } catch (err) {
    next(err);
  }
});

router.delete('/announcements/:id', async (req, res, next) => {
  try {
    const a = await loadAnnouncement(req, res);
    if (!a) return;
    deleteById('announcements', a.id);
    await emitEvent({ kind: 'announcement.deleted', entity: 'announcement', entityId: a.id });
    await auditLog({ ...audited(req), action: 'announcement.delete', target: a.id, result: 'success' });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- push jobs (read-only)

router.get('/push-jobs', validate(paginationSchema, 'query'), async (req, res, next) => {
  try {
    const { page, per_page } = req.query;
    const total = get(null, 'SELECT COUNT(*) AS c FROM push_jobs').c;
    const rows = all(
      'push_jobs',
      'SELECT * FROM push_jobs ORDER BY created_at DESC LIMIT ? OFFSET ?',
      [per_page, (page - 1) * per_page]
    );
    res.json(
      paginated(
        rows.map((j) => ({
          id: j.id,
          announcement_id: j.announcementId,
          title: j.title,
          body: j.body,
          status: j.status,
          total_targets: j.totalTargets,
          sent: j.sentCount,
          failed: j.failedCount,
          skipped: j.skippedCount,
          error: j.error,
          created_at: j.createdAt,
          finished_at: j.finishedAt,
        })),
        total,
        page,
        per_page
      )
    );
  } catch (err) {
    next(err);
  }
});

router.get('/push-jobs/:id', async (req, res, next) => {
  try {
    const job = get('push_jobs', 'SELECT * FROM push_jobs WHERE id = ?', [req.params.id]);
    if (!job) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Push job not found.' } });
    }
    const receipts = all(
      'announcement_receipts',
      `SELECT installation_id, status, reason, sent_at FROM announcement_receipts
        WHERE push_job_id = ? LIMIT 200`,
      [job.id]
    );
    res.json({
      id: job.id,
      announcement_id: job.announcementId,
      title: job.title,
      body: job.body,
      status: job.status,
      total_targets: job.totalTargets,
      sent: job.sentCount,
      failed: job.failedCount,
      skipped: job.skippedCount,
      error: job.error,
      created_at: job.createdAt,
      finished_at: job.finishedAt,
      receipts,
      receipts_note: 'Showing up to 200 receipts. Delivery "read" state is never claimed.',
    });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- statuses

const upload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, tmpDir()),
    filename: (req, file, cb) => cb(null, `${randomUUID()}.tmp`),
  }),
  limits: { fileSize: config.maxUploadMb * 1024 * 1024, files: 1 },
  fileFilter: (req, file, cb) => {
    if (ALLOWED_MIME.has(file.mimetype)) return cb(null, true);
    const err = new Error(`MIME type not allowed: ${file.mimetype}`);
    err.status = 422;
    err.code = 'unsupported_media_type';
    cb(err);
  },
});

const statusBodySchema = z.object({
  kind: z.enum(['text', 'image', 'video']),
  text: z.string().max(2000).optional(),
  // `caption` is accepted as an alias for `text` (the admin UI sends both).
  caption: z.string().max(2000).optional(),
  expires_at: z.string().datetime({ offset: true }).optional(),
  // Drafts are invisible to clients until published.
  status: z.enum(['draft', 'published']).default('published'),
});

router.get('/statuses', async (req, res, next) => {
  try {
    const rows = all('statuses', 'SELECT * FROM statuses ORDER BY created_at DESC LIMIT 100');
    res.json({ data: rows.map((s) => serializeStatus(s, config.apiPublicBase)) });
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/statuses — multipart: kind, text?, expires_at?, file?
router.post('/statuses', uploadLimiter, (req, res, next) => {
  upload.single('file')(req, res, (err) => {
    if (err) return next(err);
    next();
  });
}, async (req, res, next) => {
  try {
    await ensureUploadDir();
    const parsed = statusBodySchema.safeParse(req.body);
    if (!parsed.success) {
      if (req.file) await import('node:fs/promises').then((fs) => fs.unlink(req.file.path).catch(() => {}));
      return res.status(422).json({
        error: { code: 'validation_error', message: 'Invalid status fields.', details: parsed.error.flatten() },
      });
    }
    const { kind, expires_at } = parsed.data;
    const text = parsed.data.text ?? parsed.data.caption;

    if (kind === 'text' && (!text || !text.trim())) {
      return res.status(422).json({
        error: { code: 'validation_error', message: 'Text statuses require "text".' },
      });
    }
    if (kind !== 'text' && !req.file) {
      return res.status(422).json({
        error: { code: 'validation_error', message: `Kind "${kind}" requires an uploaded file.` },
      });
    }
    if (kind === 'text' && req.file) {
      await import('node:fs/promises').then((fs) => fs.unlink(req.file.path).catch(() => {}));
      return res.status(422).json({
        error: { code: 'validation_error', message: 'Text statuses do not accept a file.' },
      });
    }

    let expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000); // default 24h
    if (expires_at) {
      expiresAt = new Date(expires_at);
      const max = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
      if (expiresAt <= new Date() || expiresAt > max) {
        if (req.file) await import('node:fs/promises').then((fs) => fs.unlink(req.file.path).catch(() => {}));
        return res.status(422).json({
          error: { code: 'validation_error', message: 'expires_at must be in the future and within 30 days.' },
        });
      }
    }

    let mediaId = null;
    if (req.file) {
      // Magic-byte + MIME validation happens inside ingestUpload; the temp
      // file is deleted on any failure.
      const asset = await ingestUpload({
        tmpPath: req.file.path,
        originalName: req.file.originalname,
        declaredMime: req.file.mimetype,
        sizeBytes: req.file.size,
        createdBy: req.admin.id,
      });
      mediaId = asset.id;
    }

    const statusId = newId();
    const now = nowIso();
    insertRow('statuses', {
      id: statusId,
      kind,
      status: parsed.data.status,
      text: text?.trim() || null,
      media_id: mediaId,
      expires_at: expiresAt,
      published_at: parsed.data.status === 'published' ? now : null,
      created_at: now,
      updated_at: now,
    });
    const status = get('statuses', 'SELECT * FROM statuses WHERE id = ?', [statusId]);
    // Drafts are not synced to clients; publishing emits the upsert event.
    if (status.status === 'published') {
      await emitEvent({ kind: 'status.upsert', entity: 'status', entityId: status.id });
    }
    await auditLog({ ...audited(req), action: 'status.create', target: status.id, result: 'success' });
    res.status(201).json(serializeStatus(status, config.apiPublicBase));
  } catch (err) {
    next(err);
  }
});

router.delete('/statuses/:id', async (req, res, next) => {
  try {
    const status = get('statuses', 'SELECT * FROM statuses WHERE id = ?', [req.params.id]);
    if (!status) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Status not found.' } });
    }
    const mediaId = status.mediaId;

    // DB changes (row + sync event + media row) are atomic; the file itself
    // is removed from disk afterwards and orphan cleanup covers stragglers.
    let assetToDelete = null;
    await transaction(async () => {
      deleteById('statuses', status.id);
      await emitEvent({ kind: 'status.deleted', entity: 'status', entityId: status.id });
      if (mediaId) {
        const stillUsed = get(null, 'SELECT COUNT(*) AS c FROM statuses WHERE media_id = ?', [
          mediaId,
        ]).c;
        if (stillUsed === 0) {
          const asset = get('media_assets', 'SELECT * FROM media_assets WHERE id = ?', [mediaId]);
          if (asset) {
            deleteById('media_assets', mediaId);
            assetToDelete = asset;
          }
        }
      }
    });
    if (assetToDelete) {
      await deleteAssetFile(assetToDelete);
    }

    await auditLog({ ...audited(req), action: 'status.delete', target: status.id, result: 'success' });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

// --- status lifecycle: drafts, captions, publish/unpublish/archive ----------

async function loadStatus(req, res) {
  const s = get('statuses', 'SELECT * FROM statuses WHERE id = ?', [req.params.id]);
  if (!s) {
    res.status(404).json({ error: { code: 'not_found', message: 'Status not found.' } });
    return null;
  }
  return s;
}

const statusPatchSchema = z.object({
  // The `text` column is exposed to the API as `caption`.
  caption: z.string().max(500).optional(),
  expires_at: z.string().datetime({ offset: true }).optional(),
});

function validExpiry(value) {
  const d = new Date(value);
  const max = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  return d > new Date() && d <= max ? d : null;
}

// PATCH /v1/admin/statuses/:id — edit caption and/or expiry.
router.patch('/statuses/:id', validate(statusPatchSchema), async (req, res, next) => {
  try {
    const s = await loadStatus(req, res);
    if (!s) return;
    const data = { updated_at: nowIso() };
    if (req.body.caption !== undefined) {
      data.text = req.body.caption.trim() ? req.body.caption.trim() : null;
    }
    if (req.body.expires_at !== undefined) {
      const d = validExpiry(req.body.expires_at);
      if (!d) {
        return res.status(422).json({
          error: { code: 'validation_error', message: 'expires_at must be in the future and within 30 days.' },
        });
      }
      data.expires_at = d;
    }
    const updated = updateById('statuses', s.id, data);
    // Re-sync so clients pick up caption/expiry changes.
    if (updated.status === 'published') {
      await emitEvent({ kind: 'status.upsert', entity: 'status', entityId: s.id });
    }
    await auditLog({ ...audited(req), action: 'status.update', target: s.id, result: 'success' });
    res.json(serializeStatus(updated, config.apiPublicBase));
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/statuses/:id/publish — draft -> published (clients sync it).
router.post('/statuses/:id/publish', async (req, res, next) => {
  try {
    const s = await loadStatus(req, res);
    if (!s) return;
    const updated = await transaction(async () => {
      const u = updateById('statuses', s.id, {
        status: 'published',
        published_at: nowIso(),
        updated_at: nowIso(),
      });
      await emitEvent({ kind: 'status.upsert', entity: 'status', entityId: s.id });
      return u;
    });
    await auditLog({ ...audited(req), action: 'status.publish', target: s.id, result: 'success' });
    res.json(serializeStatus(updated, config.apiPublicBase));
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/statuses/:id/unpublish — published -> draft. Clients remove
// it via the sync delete event (an already-displayed push notification is
// NOT recalled — enforced through sync + cache cleanup, as documented).
router.post('/statuses/:id/unpublish', async (req, res, next) => {
  try {
    const s = await loadStatus(req, res);
    if (!s) return;
    const updated = await transaction(async () => {
      const u = updateById('statuses', s.id, { status: 'draft', updated_at: nowIso() });
      await emitEvent({ kind: 'status.deleted', entity: 'status', entityId: s.id });
      return u;
    });
    await auditLog({ ...audited(req), action: 'status.unpublish', target: s.id, result: 'success' });
    res.json(serializeStatus(updated, config.apiPublicBase));
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/statuses/:id/archive — archived statuses stay in the DB but
// are invisible to clients (sync delete event emitted).
router.post('/statuses/:id/archive', async (req, res, next) => {
  try {
    const s = await loadStatus(req, res);
    if (!s) return;
    const updated = await transaction(async () => {
      const u = updateById('statuses', s.id, { status: 'archived', updated_at: nowIso() });
      await emitEvent({ kind: 'status.deleted', entity: 'status', entityId: s.id });
      return u;
    });
    await auditLog({ ...audited(req), action: 'status.archive', target: s.id, result: 'success' });
    res.json(serializeStatus(updated, config.apiPublicBase));
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- private inbox (admin view)

const inboxQuery = paginationSchema.extend({
  status: z.enum(['pending', 'resolved', 'spam']).optional(),
  read: z.enum(['true', 'false']).optional(),
  // Archived messages are hidden by default; pass archived=true to list them.
  archived: z.enum(['true', 'false']).optional(),
});

router.get('/messages', validate(inboxQuery, 'query'), async (req, res, next) => {
  try {
    const { page, per_page, status } = req.query;
    const conds = [];
    const params = [];
    if (status) {
      conds.push('m.status = ?');
      params.push(status);
    }
    if (req.query.read === 'true') conds.push('m.read_at IS NOT NULL');
    else if (req.query.read === 'false') conds.push('m.read_at IS NULL');
    conds.push('m.archived = ?');
    params.push(req.query.archived === 'true');
    const where = conds.join(' AND ');
    const total = get(null, `SELECT COUNT(*) AS c FROM user_messages m WHERE ${where}`, params).c;
    const rows = all(
      'user_messages',
      `SELECT m.*,
              (SELECT COUNT(*) FROM admin_replies r WHERE r.message_id = m.id) AS reply_count
         FROM user_messages m
        WHERE ${where}
        ORDER BY m.created_at DESC
        LIMIT ? OFFSET ?`,
      [...params, per_page, (page - 1) * per_page]
    );
    res.json(
      paginated(
        rows.map((m) => ({
          id: m.id,
          installation_id: m.installationId,
          body: m.body,
          kind: m.kind,
          status: m.status,
          read_at: m.readAt,
          archived: m.archived,
          reply_count: m.replyCount,
          created_at: m.createdAt,
          updated_at: m.updatedAt,
        })),
        total,
        page,
        per_page
      )
    );
  } catch (err) {
    next(err);
  }
});

router.get('/messages/:id', async (req, res, next) => {
  try {
    const m = get('user_messages', 'SELECT * FROM user_messages WHERE id = ?', [req.params.id]);
    if (!m) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Message not found.' } });
    }
    const replies = all(
      'admin_replies',
      'SELECT * FROM admin_replies WHERE message_id = ? ORDER BY created_at ASC',
      [m.id]
    );
    const installation = get(
      'installations',
      'SELECT id, app_version, platform, revoked, created_at FROM installations WHERE id = ?',
      [m.installationId]
    );
    res.json({
      id: m.id,
      installation: installation
        ? {
            id: installation.id,
            appVersion: installation.appVersion,
            platform: installation.platform,
            revoked: installation.revoked,
            createdAt: installation.createdAt,
          }
        : null,
      body: m.body,
      kind: m.kind,
      status: m.status,
      read_at: m.readAt,
      archived: m.archived,
      created_at: m.createdAt,
      replies: replies.map((r) => ({ id: r.id, body: r.body, created_at: r.createdAt })),
    });
  } catch (err) {
    next(err);
  }
});

const replySchema = z.object({ body: z.string().min(1).max(5000) });

router.post('/messages/:id/replies', validate(replySchema), async (req, res, next) => {
  try {
    const m = get('user_messages', 'SELECT * FROM user_messages WHERE id = ?', [req.params.id]);
    if (!m) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Message not found.' } });
    }
    const replyId = newId();
    const now = nowIso();
    insertRow('admin_replies', {
      id: replyId,
      message_id: m.id,
      admin_user_id: req.admin.id,
      body: req.body.body,
      created_at: now,
    });
    const reply = get('admin_replies', 'SELECT * FROM admin_replies WHERE id = ?', [replyId]);
    // Private sync event so the owning installation picks up the reply.
    await emitEvent({
      kind: 'message.upsert',
      entity: 'message',
      entityId: m.id,
      installationId: m.installationId,
    });
    await auditLog({ ...audited(req), action: 'message.reply', target: m.id, result: 'success' });
    res.status(201).json({ id: reply.id, body: reply.body, created_at: reply.createdAt });
  } catch (err) {
    next(err);
  }
});

const messageStatusSchema = z.object({
  status: z.enum(['pending', 'resolved', 'spam']),
});

router.post('/messages/:id/status', validate(messageStatusSchema), async (req, res, next) => {
  try {
    const m = get('user_messages', 'SELECT * FROM user_messages WHERE id = ?', [req.params.id]);
    if (!m) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Message not found.' } });
    }
    const updated = updateById('user_messages', m.id, {
      status: req.body.status,
      updated_at: nowIso(),
    });
    await auditLog({
      ...audited(req),
      action: 'message.status',
      target: m.id,
      result: 'success',
      meta: { from: m.status, to: updated.status },
    });
    res.json({ id: updated.id, status: updated.status });
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/messages/:id/read — mark read (sets read_at) or unread.
router.post('/messages/:id/read', validate(z.object({ read: z.boolean() })), async (req, res, next) => {
  try {
    const m = get('user_messages', 'SELECT * FROM user_messages WHERE id = ?', [req.params.id]);
    if (!m) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Message not found.' } });
    }
    const updated = updateById('user_messages', m.id, {
      read_at: req.body.read ? nowIso() : null,
      updated_at: nowIso(),
    });
    await auditLog({
      ...audited(req),
      action: 'message.read',
      target: m.id,
      result: 'success',
      meta: { read: req.body.read },
    });
    res.json({ id: updated.id, read_at: updated.readAt });
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/messages/:id/archive — archive or unarchive a conversation.
router.post('/messages/:id/archive', validate(z.object({ archived: z.boolean() })), async (req, res, next) => {
  try {
    const m = get('user_messages', 'SELECT * FROM user_messages WHERE id = ?', [req.params.id]);
    if (!m) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Message not found.' } });
    }
    const updated = updateById('user_messages', m.id, {
      archived: req.body.archived,
      updated_at: nowIso(),
    });
    await auditLog({
      ...audited(req),
      action: 'message.archive',
      target: m.id,
      result: 'success',
      meta: { archived: req.body.archived },
    });
    res.json({ id: updated.id, archived: updated.archived });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- installations

const installationsQuery = paginationSchema.extend({
  q: z.string().max(64).optional(),
});

router.get('/installations', validate(installationsQuery, 'query'), async (req, res, next) => {
  try {
    const { page, per_page, q } = req.query;
    let where = '1 = 1';
    const params = [];
    if (q) {
      const pat = `%${likeEscape(q)}%`;
      where = `(i.id LIKE ? ESCAPE '\\' OR i.app_version LIKE ? ESCAPE '\\' OR i.phone_number LIKE ? ESCAPE '\\' OR i.device_name LIKE ? ESCAPE '\\')`;
      params.push(pat, pat, pat, pat);
    }
    // "Online" = heartbeat within the last 5 minutes (best-effort presence).
    const onlineCutoff = new Date(Date.now() - 5 * 60 * 1000);
    const total = get(null, `SELECT COUNT(*) AS c FROM installations i WHERE ${where}`, params).c;
    const rows = all(
      'installations',
      `SELECT i.*,
              (SELECT pt.updated_at FROM push_tokens pt WHERE pt.installation_id = i.id) AS push_token_updated_at,
              (SELECT COUNT(*) FROM user_messages m WHERE m.installation_id = i.id) AS message_count
         FROM installations i
        WHERE ${where}
        ORDER BY i.created_at DESC
        LIMIT ? OFFSET ?`,
      [...params, per_page, (page - 1) * per_page]
    );
    res.json(
      paginated(
        rows.map((i) => ({
          id: i.id,
          display_label: i.displayLabel,
          app_version: i.appVersion,
          platform: i.platform,
          locale: i.locale,
          phone_number: i.phoneNumber,
          device_name: i.deviceName,
          last_sync_at: i.lastSyncAt,
          last_seen_at: i.lastSeenAt,
          online: !!i.lastSeenAt && i.lastSeenAt > onlineCutoff,
          revoked: i.revoked,
          revoked_at: i.revokedAt,
          revoked_reason: i.revokedReason,
          blocked: i.blocked,
          blocked_at: i.blockedAt,
          blocked_reason: i.blockedReason,
          has_push_token: i.pushTokenUpdatedAt != null,
          push_token_updated_at: i.pushTokenUpdatedAt ? new Date(i.pushTokenUpdatedAt) : null,
          message_count: i.messageCount,
          created_at: i.createdAt,
        })),
        total,
        page,
        per_page
      )
    );
  } catch (err) {
    next(err);
  }
});

const revokeSchema = z.object({ reason: z.string().max(500).optional() });

router.post('/installations/:id/revoke', validate(revokeSchema), async (req, res, next) => {
  try {
    const inst = get('installations', 'SELECT * FROM installations WHERE id = ?', [req.params.id]);
    if (!inst) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Installation not found.' } });
    }
    const now = nowIso();
    await transaction(() => {
      run('UPDATE installations SET revoked = 1, revoked_at = ?, revoked_reason = ?, updated_at = ? WHERE id = ?', [
        now,
        req.body.reason ?? 'admin_revoke',
        now,
        inst.id,
      ]);
      run('DELETE FROM push_tokens WHERE installation_id = ?', [inst.id]);
    });
    await auditLog({
      ...audited(req),
      action: 'installation.revoke',
      target: inst.id,
      result: 'success',
      meta: { reason: req.body.reason ?? 'admin_revoke' },
    });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

router.post('/installations/:id/unrevoke', async (req, res, next) => {
  try {
    const inst = get('installations', 'SELECT * FROM installations WHERE id = ?', [req.params.id]);
    if (!inst) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Installation not found.' } });
    }
    run('UPDATE installations SET revoked = 0, revoked_at = NULL, revoked_reason = NULL, updated_at = ? WHERE id = ?', [
      nowIso(),
      inst.id,
    ]);
    await auditLog({ ...audited(req), action: 'installation.unrevoke', target: inst.id, result: 'success' });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

const blockSchema = z.object({ reason: z.string().min(3).max(500) });

// POST /v1/admin/installations/:id/block — abuse control. Blocked
// installations get 403 `installation_blocked` on every installation endpoint.
router.post('/installations/:id/block', validate(blockSchema), async (req, res, next) => {
  try {
    const inst = get('installations', 'SELECT * FROM installations WHERE id = ?', [req.params.id]);
    if (!inst) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Installation not found.' } });
    }
    const now = nowIso();
    await transaction(() => {
      run('UPDATE installations SET blocked = 1, blocked_reason = ?, blocked_at = ?, updated_at = ? WHERE id = ?', [
        req.body.reason,
        now,
        now,
        inst.id,
      ]);
      run('DELETE FROM push_tokens WHERE installation_id = ?', [inst.id]);
    });
    await auditLog({
      ...audited(req),
      action: 'installation.block',
      target: inst.id,
      result: 'success',
      meta: { reason: req.body.reason },
    });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

router.post('/installations/:id/unblock', async (req, res, next) => {
  try {
    const inst = get('installations', 'SELECT * FROM installations WHERE id = ?', [req.params.id]);
    if (!inst) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Installation not found.' } });
    }
    run('UPDATE installations SET blocked = 0, blocked_reason = NULL, blocked_at = NULL, updated_at = ? WHERE id = ?', [
      nowIso(),
      inst.id,
    ]);
    await auditLog({ ...audited(req), action: 'installation.unblock', target: inst.id, result: 'success' });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

const labelSchema = z.object({
  display_label: z.string().max(120).nullable().optional(),
});

// PUT /v1/admin/installations/:id/label — set a friendly admin-side label.
router.put('/installations/:id/label', validate(labelSchema), async (req, res, next) => {
  try {
    const inst = get('installations', 'SELECT * FROM installations WHERE id = ?', [req.params.id]);
    if (!inst) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Installation not found.' } });
    }
    const label = req.body.display_label?.trim() ? req.body.display_label.trim() : null;
    const updated = updateById('installations', inst.id, { display_label: label, updated_at: nowIso() });
    await auditLog({
      ...audited(req),
      action: 'installation.label',
      target: inst.id,
      result: 'success',
      meta: { display_label: label },
    });
    res.json({ id: updated.id, display_label: updated.displayLabel });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- media library

router.get('/media', validate(paginationSchema, 'query'), async (req, res, next) => {
  try {
    const { page, per_page } = req.query;
    const total = get(null, 'SELECT COUNT(*) AS c FROM media_assets').c;
    const rows = all(
      'media_assets',
      `SELECT m.*,
              (SELECT COUNT(*) FROM statuses s WHERE s.media_id = m.id) AS status_count
         FROM media_assets m
        ORDER BY m.created_at DESC
        LIMIT ? OFFSET ?`,
      [per_page, (page - 1) * per_page]
    );
    res.json(
      paginated(
        rows.map((m) => ({
          id: m.id,
          original_name: m.originalName,
          mime: m.mime,
          size_bytes: m.sizeBytes,
          sha256: m.sha256,
          referenced_by_statuses: m.statusCount,
          preview_url: `${config.apiPublicBase}/v1/media/${m.id}`,
          created_at: m.createdAt,
        })),
        total,
        page,
        per_page
      )
    );
  } catch (err) {
    next(err);
  }
});

router.delete('/media/:id', async (req, res, next) => {
  try {
    const asset = get(
      'media_assets',
      `SELECT *, (SELECT COUNT(*) FROM statuses s WHERE s.media_id = media_assets.id) AS status_count
         FROM media_assets WHERE id = ?`,
      [req.params.id]
    );
    if (!asset) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Media not found.' } });
    }
    if (asset.statusCount > 0) {
      return res.status(409).json({
        error: {
          code: 'media_referenced',
          message: 'Media is referenced by a status. Delete the status first (orphan cleanup also handles this).',
        },
      });
    }
    await deleteAssetFile(asset);
    deleteById('media_assets', asset.id);
    await auditLog({ ...audited(req), action: 'media.delete', target: asset.id, result: 'success' });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

// ---------------------------------------------------------------- 2FA (TOTP)

// GET /v1/admin/2fa/status — 2FA status badge: is TOTP enabled for this admin?
router.get('/2fa/status', async (req, res, next) => {
  try {
    res.json({ totp_enabled: !!req.admin.totpEnabled });
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/2fa/setup — generate a secret and provisioning URI.
// TOTP stays DISABLED until the secret is confirmed via /2fa/verify.
router.post('/2fa/setup', async (req, res, next) => {
  try {
    const secret = authenticator.generateSecret();
    run('UPDATE admin_users SET totp_secret = ?, totp_enabled = 0, updated_at = ? WHERE id = ?', [
      secret,
      nowIso(),
      req.admin.id,
    ]);
    // Fixed issuer; never embeds secrets beyond the provisioning URI itself.
    const otpauthUrl = authenticator.keyuri(req.admin.username, 'IMAYX', secret);
    await auditLog({ ...audited(req), action: 'admin.2fa.setup', result: 'success' });
    res.json({ secret, otpauth_url: otpauthUrl });
  } catch (err) {
    next(err);
  }
});

const totpCodeSchema = z.object({ code: z.string().min(6).max(12) });

// POST /v1/admin/2fa/verify — confirm the secret with a current code,
// enabling TOTP for this admin.
router.post('/2fa/verify', validate(totpCodeSchema), async (req, res, next) => {
  try {
    const user = get('admin_users', 'SELECT * FROM admin_users WHERE id = ?', [req.admin.id]);
    if (!user?.totpSecret) {
      return res.status(409).json({
        error: { code: 'no_totp_secret', message: 'Run 2FA setup first.' },
      });
    }
    const token = String(req.body.code).replace(/[\s-]/g, '');
    let valid = false;
    try {
      valid = authenticator.verify({ token, secret: user.totpSecret });
    } catch {
      valid = false;
    }
    if (!valid) {
      await auditLog({ ...audited(req), action: 'admin.2fa.verify', result: 'failure' });
      return res.status(401).json({
        error: { code: 'invalid_totp', message: 'Invalid code.' },
      });
    }
    run('UPDATE admin_users SET totp_enabled = 1, updated_at = ? WHERE id = ?', [nowIso(), user.id]);
    await auditLog({ ...audited(req), action: 'admin.2fa.verify', result: 'success' });
    res.json({ ok: true, totp_enabled: true });
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/2fa/disable — requires the admin's password.
router.post(
  '/2fa/disable',
  validate(z.object({ password: z.string().min(1).max(256) })),
  async (req, res, next) => {
    try {
      const user = get('admin_users', 'SELECT * FROM admin_users WHERE id = ?', [req.admin.id]);
      let ok = false;
      if (user) {
        try {
          ok = await bcrypt.compare(req.body.password, user.passwordHash);
        } catch {
          ok = false;
        }
      }
      if (!ok) {
        await auditLog({ ...audited(req), action: 'admin.2fa.disable', result: 'failure' });
        return res.status(401).json({
          error: { code: 'invalid_credentials', message: 'Invalid username or password.' },
        });
      }
      run('UPDATE admin_users SET totp_secret = NULL, totp_enabled = 0, updated_at = ? WHERE id = ?', [
        nowIso(),
        user.id,
      ]);
      await auditLog({ ...audited(req), action: 'admin.2fa.disable', result: 'success' });
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  }
);

// ---------------------------------------------------------------- admin sessions

// GET /v1/admin/sessions — active sessions for this admin. Only the last 8
// chars of each session id are exposed; revocation accepts that suffix.
router.get('/sessions', async (req, res, next) => {
  try {
    const rows = all(
      'admin_sessions',
      `SELECT * FROM admin_sessions
        WHERE admin_user_id = ? AND revoked_at IS NULL AND expires_at > ?
        ORDER BY created_at DESC`,
      [req.admin.id, new Date()]
    );
    res.json({
      data: rows.map((s) => ({
        id: s.id.slice(-8),
        ip: s.ipTrunc,
        user_agent: s.userAgent ? s.userAgent.slice(0, 120) : null,
        created_at: s.createdAt,
        expires_at: s.expiresAt,
        current: s.id === req.adminSession.id,
      })),
    });
  } catch (err) {
    next(err);
  }
});

async function findOwnSession(adminId, idParam) {
  const rows = all(
    'admin_sessions',
    `SELECT * FROM admin_sessions
      WHERE admin_user_id = ? AND revoked_at IS NULL AND expires_at > ?`,
    [adminId, new Date()]
  );
  const exact = rows.find((s) => s.id === idParam);
  if (exact) return { session: exact };
  const suffixed = rows.filter((s) => s.id.endsWith(idParam));
  if (suffixed.length === 1) return { session: suffixed[0] };
  if (suffixed.length > 1) return { ambiguous: true };
  return {};
}

// DELETE /v1/admin/sessions/:id — revoke one session (full id or last-8 suffix).
// Revoking the current session also clears the cookie.
router.delete('/sessions/:id', async (req, res, next) => {
  try {
    const found = await findOwnSession(req.admin.id, req.params.id);
    if (found.ambiguous) {
      return res.status(409).json({
        error: { code: 'ambiguous_session', message: 'Multiple sessions match; be more specific.' },
      });
    }
    if (!found.session) {
      return res.status(404).json({ error: { code: 'not_found', message: 'Session not found.' } });
    }
    run('UPDATE admin_sessions SET revoked_at = ? WHERE id = ?', [nowIso(), found.session.id]);
    const isCurrent = found.session.id === req.adminSession.id;
    if (isCurrent) {
      res.clearCookie(config.adminCookieName, { path: '/' });
    }
    await auditLog({
      ...audited(req),
      action: 'admin.session.revoke',
      target: found.session.id.slice(-8),
      result: 'success',
      meta: { current: isCurrent },
    });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/sessions/revoke-all — emergency revocation.
// Body `{ except_current: true }` revokes every session EXCEPT the caller's
// current one (keeps the cookie); absent/false revokes ALL including current
// (and clears the cookie).
router.post(
  '/sessions/revoke-all',
  validate(z.object({ except_current: z.boolean().optional() })),
  async (req, res, next) => {
    try {
      const exceptCurrent = req.body.except_current === true;
      const params = [nowIso(), req.admin.id];
      let sql = 'UPDATE admin_sessions SET revoked_at = ? WHERE admin_user_id = ? AND revoked_at IS NULL';
      if (exceptCurrent) {
        sql += ' AND id != ?';
        params.push(req.adminSession.id);
      }
      run(sql, params);
      if (!exceptCurrent) {
        res.clearCookie(config.adminCookieName, { path: '/' });
      }
      await auditLog({
        ...audited(req),
        action: 'admin.session.revoke_all',
        result: 'success',
        meta: { except_current: exceptCurrent },
      });
      res.json({ ok: true });
    } catch (err) {
      next(err);
    }
  }
);

// ---------------------------------------------------------------- audit logs

router.get('/audit-logs', validate(paginationSchema, 'query'), async (req, res, next) => {
  try {
    const { page, per_page } = req.query;
    const total = get(null, 'SELECT COUNT(*) AS c FROM audit_logs').c;
    const rows = all(
      'audit_logs',
      'SELECT * FROM audit_logs ORDER BY id DESC LIMIT ? OFFSET ?',
      [per_page, (page - 1) * per_page]
    );
    res.json(
      paginated(
        rows.map((a) => ({
          id: a.id.toString(),
          actor: a.actor,
          action: a.action,
          target: a.target,
          result: a.result,
          ip: a.ipTrunc,
          meta: a.meta,
          created_at: a.createdAt,
        })),
        total,
        page,
        per_page
      )
    );
  } catch (err) {
    next(err);
  }
});

export default router;
