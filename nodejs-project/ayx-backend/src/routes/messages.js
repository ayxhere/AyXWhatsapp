// Private user messaging.
// PRIVACY (non-negotiable): every read is scoped with
// `WHERE installation_id = <caller>`. There is no endpoint, parameter, or
// search that can return another installation's messages.

import { Router } from 'express';
import { z } from 'zod';
import { get, all, insertRow, newId, nowIso } from '../db.js';
import { installationAuth } from '../auth-installation.js';
import { validate, paginationSchema, paginated } from '../middleware/validate.js';
import { messageLimiter } from './installations.js';
import { emitEvent, serializeMessage } from '../services/sync.js';

const router = Router();

const messageSchema = z.object({
  body: z.string().min(1).max(2000),
  kind: z.enum(['feedback', 'bug', 'other']),
});

// POST /v1/messages — send a private message to the admin.
router.post(
  '/',
  installationAuth,
  messageLimiter,
  validate(messageSchema),
  async (req, res, next) => {
    try {
      const msgId = newId();
      const now = nowIso();
      insertRow('user_messages', {
        id: msgId,
        installation_id: req.installation.id,
        body: req.body.body,
        kind: req.body.kind,
        status: 'pending',
        created_at: now,
        updated_at: now,
      });
      // Private sync event scoped to this installation only.
      await emitEvent({
        kind: 'message.upsert',
        entity: 'message',
        entityId: msgId,
        installationId: req.installation.id,
      });
      res.status(201).json({ message_id: msgId, status: 'pending' });
    } catch (err) {
      next(err);
    }
  }
);

// GET /v1/messages/mine — ONLY the caller's own messages, with admin replies.
router.get('/mine', installationAuth, validate(paginationSchema, 'query'), async (req, res, next) => {
  try {
    const { page, per_page } = req.query;
    const where = 'm.installation_id = ?';
    const params = [req.installation.id];
    const total = get(null, `SELECT COUNT(*) AS c FROM user_messages m WHERE ${where}`, params).c;
    const rows = all(
      'user_messages',
      `SELECT * FROM user_messages m WHERE ${where} ORDER BY m.created_at DESC LIMIT ? OFFSET ?`,
      [...params, per_page, (page - 1) * per_page]
    );
    // Attach replies per message (N+1 is fine at this scale; privacy-scoped).
    const data = rows.map((m) => {
      const replies = all(
        'admin_replies',
        'SELECT * FROM admin_replies WHERE message_id = ? ORDER BY created_at ASC',
        [m.id]
      );
      return { ...serializeMessage({ ...m, replies }) };
    });
    res.json(paginated(data, total, page, per_page));
  } catch (err) {
    next(err);
  }
});

export default router;
