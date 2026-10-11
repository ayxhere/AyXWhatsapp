// Admin sign-in / sign-out + TOTP challenge completion.
// - Passwords hashed with bcrypt (cost 12).
// - Generic 401 `invalid_credentials` whether the username is unknown or the
//   password is wrong (no account-enumeration oracle).
// - Brute-force lockout: 5 failed attempts (password OR TOTP code) lock the
//   ATTEMPTED username for 15 minutes -> 423 `account_locked`. Lockout state
//   is tracked uniformly even for unknown usernames, so the lockout response
//   never reveals account existence.
// - Optional TOTP 2FA: when enabled, password login returns a short-lived
//   single-use challenge; the session is created only after
//   POST /v1/admin/login/totp verifies the code.
// - Successful login sets an HttpOnly, Secure (in production), SameSite=Lax
//   session cookie and returns a CSRF token for mutating requests.
// - Rate limited: 10 login attempts / minute / IP (separate budget for the
//   TOTP completion endpoint).

import { randomBytes, createHash } from 'node:crypto';
import { Router } from 'express';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import bcrypt from 'bcryptjs';
import { authenticator } from 'otplib';
import { get, run, insertRow, transaction, newId, nowIso } from '../db.js';
import { config } from '../config.js';
import { adminAuth } from '../auth-admin.js';
import { validate } from '../middleware/validate.js';
import { clientIp, truncateIp } from '../middleware/ip.js';
import { auditLog } from '../services/audit.js';

const router = Router();

const LOCKOUT_THRESHOLD = 5; // failed attempts before lockout
const LOCKOUT_MINUTES = 15;
const TOTP_CHALLENGE_MINUTES = 5;

// Accept the current 30s step plus one step of clock skew either way —
// authenticator apps and the server rarely agree on the exact second.
authenticator.options = { window: 1 };

function hashChallenge(challenge) {
  return createHash('sha256').update(challenge, 'utf8').digest('hex');
}

const rateLimitBody = (code, message) => (req, res) =>
  res.status(429).json({ error: { code, message } });

const loginLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 10,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  keyGenerator: (req) => clientIp(req),
  handler: rateLimitBody('rate_limited', 'Too many login attempts, please slow down.'),
});

const totpLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 10,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  keyGenerator: (req) => clientIp(req),
  handler: rateLimitBody('rate_limited', 'Too many code attempts, please slow down.'),
});

const loginSchema = z.object({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(256),
});

const totpCompleteSchema = z.object({
  challenge: z.string().min(1).max(256),
  code: z.string().min(6).max(12),
});

function invalidCredentials(res) {
  return res.status(401).json({
    error: { code: 'invalid_credentials', message: 'Invalid username or password.' },
  });
}

function lockedResponse(res, retryAfterSeconds) {
  return res.status(423).json({
    error: {
      code: 'account_locked',
      message: 'Too many failed attempts. Try again later.',
      retry_after_seconds: retryAfterSeconds,
    },
  });
}

function cookieOptions() {
  return {
    httpOnly: true,
    secure: config.isProd, // production is always HTTPS (Cloudflare)
    sameSite: 'lax',
    path: '/',
    maxAge: config.adminSessionHours * 60 * 60 * 1000,
  };
}

// A constant bcrypt hash used for dummy verification when the username does
// not exist, so response timing does not reveal account existence.
let dummyHash = null;
async function getDummyHash() {
  if (!dummyHash) dummyHash = await bcrypt.hash('dummy-password-for-timing', 12);
  return dummyHash;
}

// --- brute-force lockout ---------------------------------------------------
// Keyed on the ATTEMPTED username uniformly: unknown usernames accumulate a
// lockout row exactly like real ones, so the 423 response is not an oracle.

async function lockoutRetryAfter(username) {
  const row = get('admin_lockouts', 'SELECT * FROM admin_lockouts WHERE username = ?', [username]);
  if (!row?.lockedUntil) return 0;
  const ms = row.lockedUntil.getTime() - Date.now();
  return ms > 0 ? Math.ceil(ms / 1000) : 0;
}

async function recordLoginFailure(username) {
  const now = nowIso();
  // Upsert: new rows start at 1 failure, existing rows increment.
  const row = get(
    null,
    `INSERT INTO admin_lockouts (username, failures, updated_at)
     VALUES (?, 1, ?)
     ON CONFLICT(username) DO UPDATE
       SET failures = admin_lockouts.failures + 1, updated_at = excluded.updated_at
     RETURNING failures`,
    [username, now]
  );
  if (row.failures >= LOCKOUT_THRESHOLD) {
    run('UPDATE admin_lockouts SET locked_until = ?, updated_at = ? WHERE username = ?', [
      new Date(Date.now() + LOCKOUT_MINUTES * 60 * 1000),
      nowIso(),
      username,
    ]);
  }
  return row.failures;
}

async function clearLockout(username) {
  run('DELETE FROM admin_lockouts WHERE username = ?', [username]);
}

// --- session creation (shared by password-only and TOTP logins) ------------

async function createSessionAndRespond(req, res, user, ip) {
  const sessionId = randomBytes(48).toString('hex');
  const csrfToken = randomBytes(32).toString('hex');
  const expiresAt = new Date(Date.now() + config.adminSessionHours * 60 * 60 * 1000);

  await transaction(() => {
    insertRow('admin_sessions', {
      id: sessionId,
      admin_user_id: user.id,
      csrf_token: csrfToken,
      ip_trunc: truncateIp(ip),
      user_agent: String(req.headers['user-agent'] ?? '').slice(0, 512) || null,
      created_at: nowIso(),
      expires_at: expiresAt,
    });
    run('UPDATE admin_users SET last_login_at = ?, updated_at = ? WHERE id = ?', [
      nowIso(),
      nowIso(),
      user.id,
    ]);
  });

  await auditLog({
    adminUserId: user.id,
    actor: user.username,
    action: 'admin.login',
    result: 'success',
    ip: truncateIp(ip),
  });

  res.cookie(config.adminCookieName, sessionId, cookieOptions());
  res.json({ ok: true, csrf_token: csrfToken });
}

// POST /v1/admin/login
router.post('/login', loginLimiter, validate(loginSchema), async (req, res, next) => {
  const ip = clientIp(req);
  try {
    const { username, password } = req.body;

    // Lockout is checked BEFORE the password so locked accounts cannot be
    // brute-forced during the lock window (and no timing oracle is added).
    const retryAfter = await lockoutRetryAfter(username);
    if (retryAfter > 0) {
      await auditLog({
        adminUserId: null,
        actor: username,
        action: 'admin.login.locked',
        result: 'failure',
        ip: truncateIp(ip),
      });
      return lockedResponse(res, retryAfter);
    }

    const user = get('admin_users', 'SELECT * FROM admin_users WHERE username = ?', [username]);

    let ok = false;
    if (user && !user.disabled) {
      try {
        ok = await bcrypt.compare(password, user.passwordHash);
      } catch {
        ok = false;
      }
    } else {
      // Dummy verify to equalize timing; result discarded.
      try {
        await bcrypt.compare(password, await getDummyHash());
      } catch {
        /* ignore */
      }
      ok = false;
    }

    if (!ok) {
      const failures = await recordLoginFailure(username);
      await auditLog({
        adminUserId: user?.id ?? null,
        actor: username,
        action: 'admin.login',
        result: 'failure',
        ip: truncateIp(ip),
        meta: { failures },
      });
      if (failures >= LOCKOUT_THRESHOLD) {
        return lockedResponse(res, LOCKOUT_MINUTES * 60);
      }
      return invalidCredentials(res);
    }

    await clearLockout(username);

    // TOTP required: do NOT create a session yet — issue a short-lived,
    // single-use challenge instead.
    if (user.totpEnabled) {
      const challenge = randomBytes(32).toString('hex');
      insertRow('admin_totp_challenges', {
        id: newId(),
        admin_user_id: user.id,
        challenge_hash: hashChallenge(challenge),
        expires_at: new Date(Date.now() + TOTP_CHALLENGE_MINUTES * 60 * 1000),
        created_at: nowIso(),
      });
      await auditLog({
        adminUserId: user.id,
        actor: user.username,
        action: 'admin.login.totp_challenge',
        result: 'success',
        ip: truncateIp(ip),
      });
      return res.json({ totp_required: true, challenge });
    }

    await createSessionAndRespond(req, res, user, ip);
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/login/totp — completes a TOTP login challenge.
router.post('/login/totp', totpLimiter, validate(totpCompleteSchema), async (req, res, next) => {
  const ip = clientIp(req);
  try {
    const { challenge, code } = req.body;

    const row = get(
      'admin_totp_challenges',
      'SELECT * FROM admin_totp_challenges WHERE challenge_hash = ?',
      [hashChallenge(challenge)]
    );
    const user = row
      ? get('admin_users', 'SELECT * FROM admin_users WHERE id = ?', [row.adminUserId])
      : null;

    const burnAndReject = async () => {
      if (row) {
        run('DELETE FROM admin_totp_challenges WHERE id = ?', [row.id]);
      }
      return res.status(401).json({
        error: { code: 'invalid_totp', message: 'Invalid code or challenge.' },
      });
    };

    if (!row || row.expiresAt <= new Date() || !user || user.disabled || !user.totpEnabled || !user.totpSecret) {
      return burnAndReject();
    }

    const token = String(code).replace(/[\s-]/g, '');
    let valid = false;
    try {
      valid = authenticator.verify({ token, secret: user.totpSecret });
    } catch {
      valid = false;
    }

    if (!valid) {
      // Failed TOTP codes count toward the brute-force lockout.
      await recordLoginFailure(user.username);
      await auditLog({
        adminUserId: user.id,
        actor: user.username,
        action: 'admin.login.totp',
        result: 'failure',
        ip: truncateIp(ip),
      });
      return burnAndReject();
    }

    // Single-use: burn the challenge, clear any lockout, create the session.
    run('DELETE FROM admin_totp_challenges WHERE id = ?', [row.id]);
    await clearLockout(user.username);
    await auditLog({
      adminUserId: user.id,
      actor: user.username,
      action: 'admin.login.totp',
      result: 'success',
      ip: truncateIp(ip),
    });
    await createSessionAndRespond(req, res, user, ip);
  } catch (err) {
    next(err);
  }
});

// POST /v1/admin/logout — revokes the server-side session and clears the cookie.
router.post('/logout', adminAuth, async (req, res, next) => {
  try {
    run('UPDATE admin_sessions SET revoked_at = ? WHERE id = ?', [nowIso(), req.adminSession.id]);
    await auditLog({
      adminUserId: req.admin.id,
      actor: req.admin.username,
      action: 'admin.logout',
      result: 'success',
      ip: truncateIp(clientIp(req)),
    });
    res.clearCookie(config.adminCookieName, { path: '/' });
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

export default router;
