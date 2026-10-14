// Installation lifecycle: register / rotate token / revoke / push token.
// Rate limits: register 10/min/IP, push-token 30/min/IP, messages 20/hr/installation.

import { randomBytes, randomUUID } from 'node:crypto';
import { Router } from 'express';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { get, run, insertRow, transaction, nowIso } from '../db.js';
import { installationAuth, hashToken } from '../auth-installation.js';
import { validate } from '../middleware/validate.js';
import { clientIp } from '../middleware/ip.js';

const router = Router();

const rateLimitBody = (req, res) =>
  res.status(429).json({
    error: { code: 'rate_limited', message: 'Too many requests, please slow down.' },
  });

export const registerLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 10,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  keyGenerator: (req) => clientIp(req),
  handler: rateLimitBody,
});

const pushTokenLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 30,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  keyGenerator: (req) => clientIp(req),
  handler: rateLimitBody,
});

export const messageLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 20,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  // Per-installation bucket — applied AFTER installationAuth.
  keyGenerator: (req) => (req.installation ? `inst:${req.installation.id}` : clientIp(req)),
  handler: rateLimitBody,
});

const heartbeatLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 60,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  // Per-installation bucket — applied AFTER installationAuth.
  keyGenerator: (req) => (req.installation ? `inst:${req.installation.id}` : clientIp(req)),
  handler: rateLimitBody,
});

const registerSchema = z.object({
  app_version: z.string().min(1).max(32).optional(),
  platform: z.string().min(1).max(32).optional(),
  locale: z.string().min(1).max(16).optional(),
  phone_number: z.string().min(4).max(20).optional(),
  device_name: z.string().min(1).max(128).optional(),
});

const profileSchema = z.object({
  phone_number: z.string().min(4).max(20).optional(),
  device_name: z.string().min(1).max(128).optional(),
});

const pushTokenSchema = z.object({
  fcm_token: z.string().min(1).max(4096),
});

function newTokenPair() {
  return {
    installationId: randomUUID(),
    accessToken: randomBytes(48).toString('hex'), // 48 random bytes, hex
  };
}

// POST /v1/installations/register — anonymous installation, no login.
router.post('/register', registerLimiter, validate(registerSchema), async (req, res, next) => {
  try {
    const { installationId, accessToken } = newTokenPair();
    const now = nowIso();
    insertRow('installations', {
      id: installationId,
      token_hash: hashToken(accessToken),
      app_version: req.body.app_version ?? null,
      platform: req.body.platform ?? null,
      locale: req.body.locale ?? null,
      phone_number: req.body.phone_number ?? null,
      device_name: req.body.device_name ?? null,
      created_at: now,
      updated_at: now,
    });
    // The access token is returned ONCE and never stored in plaintext.
    res.status(201).json({ installation_id: installationId, access_token: accessToken });
  } catch (err) {
    next(err);
  }
});

// POST /v1/installations/profile — update phone_number/device_name (auth required).
router.post('/profile', installationAuth, validate(profileSchema), async (req, res, next) => {
  try {
    const updates = {};
    if (req.body.phone_number !== undefined) updates.phone_number = req.body.phone_number;
    if (req.body.device_name !== undefined) updates.device_name = req.body.device_name;
    if (Object.keys(updates).length > 0) {
      updates.updated_at = nowIso();
      const sets = Object.keys(updates).map((k) => `${k} = ?`).join(', ');
      run(`UPDATE installations SET ${sets} WHERE id = ?`, [...Object.values(updates), req.installation.id]);
    }
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});
  } catch (err) {
    next(err);
  }
});

// POST /v1/installations/token — rotate credentials (old token invalidated).
router.post('/token', installationAuth, async (req, res, next) => {
  try {
    const accessToken = randomBytes(48).toString('hex');
    run('UPDATE installations SET token_hash = ?, updated_at = ? WHERE id = ?', [
      hashToken(accessToken),
      nowIso(),
      req.installation.id,
    ]);
    res.json({ access_token: accessToken });
  } catch (err) {
    next(err);
  }
});

// POST /v1/installations/revoke — self-revoke (e.g. app uninstall/reset).
router.post('/revoke', installationAuth, async (req, res, next) => {
  try {
    const now = nowIso();
    await transaction(() => {
      run('UPDATE installations SET revoked = 1, revoked_at = ?, revoked_reason = ?, updated_at = ? WHERE id = ?', [
        now,
        'self_revoke',
        now,
        req.installation.id,
      ]);
      run('DELETE FROM push_tokens WHERE installation_id = ?', [req.installation.id]);
    });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

// POST /v1/installations/heartbeat — lightweight liveness ping. Updates
// last_seen_at, which drives the admin "online" indicator. Not a presence
// guarantee — just the last time the app checked in.
router.post('/heartbeat', heartbeatLimiter, installationAuth, async (req, res, next) => {
  try {
    const now = new Date();
    run('UPDATE installations SET last_seen_at = ?, updated_at = ? WHERE id = ?', [
      now,
      nowIso(),
      req.installation.id,
    ]);
    res.json({ ok: true, server_time: now.toISOString() });
  } catch (err) {
    next(err);
  }
});

// PUT /v1/installations/push-token — register/update FCM registration token.
router.put('/push-token', pushTokenLimiter, installationAuth, validate(pushTokenSchema), async (req, res, next) => {
  try {
    const now = nowIso();
    run(
      `INSERT INTO push_tokens (installation_id, fcm_token, created_at, updated_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(installation_id) DO UPDATE
         SET fcm_token = excluded.fcm_token, updated_at = excluded.updated_at`,
      [req.installation.id, req.body.fcm_token, now, now]
    );
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

export default router;
