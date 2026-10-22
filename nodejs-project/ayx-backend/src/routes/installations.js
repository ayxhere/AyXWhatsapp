// Installation lifecycle: register / rotate token / revoke / push token.
// Rate limits: register 10/min/IP, push-token 30/min/IP, messages 20/hr/installation.

import { randomBytes, randomUUID } from 'node:crypto';
import { Router } from 'express';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { get, run, insertRow, transaction, nowIso } from '../db.js';
import { config } from '../config.js';
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
  // Stable installation identity (app v145+): the app persists this id
  // separately from its token and re-sends it on every re-register, so a
  // token heal upserts the same row instead of minting a duplicate.
  installation_id: z.string().min(1).max(64).optional(),
  app_version: z.string().min(1).max(32).optional(),
  platform: z.string().min(1).max(32).optional(),
  locale: z.string().min(1).max(16).optional(),
  phone_number: z.string().min(4).max(20).optional(),
  device_name: z.string().min(1).max(128).optional(),
  app_signature: z.string().min(1).max(128).optional(),
});

const profileSchema = z.object({
  phone_number: z.string().min(4).max(20).optional(),
  device_name: z.string().min(1).max(128).optional(),
});

const pushTokenSchema = z.object({
  fcm_token: z.string().min(1).max(4096),
});

function newAccessToken() {
  return randomBytes(48).toString('hex'); // 48 random bytes, hex
}

// App-signature enforcement — stateless compare only; the presented signature
// is never stored. OFF entirely when APP_SIGNATURE_SHA256 is not configured
// (config.appSignatureSha256 is '' then), preserving the old behavior.
// Compare is format-insensitive: colons stripped, case-insensitive.
function signatureAccepted(provided) {
  const expected = config.appSignatureSha256;
  if (!expected) return true;
  if (typeof provided !== 'string') return false;
  return provided.replace(/:/g, '').toUpperCase() === expected;
}

function rejectSignatureMismatch(res) {
  return res.status(403).json({
    error: { code: 'signature_mismatch', message: 'App signature not recognized.' },
  });
}

// One phone number = one installation row in the panel. Whenever a number is
// saved for installation `keepId`, every OTHER row carrying the same number
// is a stale duplicate (an earlier registration of the same number) and is
// deleted — the panel must never show two rows for one number.
function deleteDuplicatePhoneRows(phone, keepId) {
  if (!phone) return;
  run(
    'DELETE FROM push_tokens WHERE installation_id IN (SELECT id FROM installations WHERE phone_number = ? AND id != ?)',
    [phone, keepId]
  );
  run('DELETE FROM installations WHERE phone_number = ? AND id != ?', [phone, keepId]);
}

// POST /v1/installations/register — anonymous installation, no login.
// Identity is stable (app v145+): when the payload carries an
// installation_id, register is an UPSERT — a known id rotates the token on
// its existing row (same id back, no duplicate), an unknown id creates the
// row under that id. No id in the payload keeps the legacy behaviour
// (fresh uuid).
router.post('/register', registerLimiter, validate(registerSchema), async (req, res, next) => {
  try {
    // Signature gate runs BEFORE any row is created (enforcement ON only).
    if (!signatureAccepted(req.body.app_signature)) return rejectSignatureMismatch(res);
    const accessToken = newAccessToken();
    const now = nowIso();
    const providedId = req.body.installation_id || null;
    let installationId = providedId;
    await transaction(() => {
      const existing = providedId
        ? get(null, 'SELECT id FROM installations WHERE id = ?', [providedId])
        : null;
      if (existing) {
        // Same installation re-registering (e.g. after a token heal): rotate
        // the token and refresh the mutable fields on the SAME row. Fields
        // absent from the payload keep their stored values.
        const updates = {
          token_hash: hashToken(accessToken),
          last_seen_at: now,
          updated_at: now,
        };
        if (req.body.app_version !== undefined) updates.app_version = req.body.app_version;
        if (req.body.platform !== undefined) updates.platform = req.body.platform;
        if (req.body.locale !== undefined) updates.locale = req.body.locale;
        if (req.body.phone_number) updates.phone_number = req.body.phone_number;
        if (req.body.device_name) updates.device_name = req.body.device_name;
        const sets = Object.keys(updates).map((k) => `${k} = ?`).join(', ');
        run(`UPDATE installations SET ${sets} WHERE id = ?`, [
          ...Object.values(updates),
          providedId,
        ]);
      } else {
        installationId = providedId || randomUUID();
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
      }
      if (req.body.phone_number) {
        deleteDuplicatePhoneRows(req.body.phone_number, installationId);
      }
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
      // A number that moved to this installation must not linger on an old
      // duplicate row.
      if (updates.phone_number) {
        deleteDuplicatePhoneRows(updates.phone_number, req.installation.id);
      }
    }
    res.json({ ok: true });
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
    // Token validity has already gated in installationAuth; the signature
    // check runs here, where the body is visible (enforcement ON only).
    if (!signatureAccepted(req.body?.app_signature)) return rejectSignatureMismatch(res);
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
