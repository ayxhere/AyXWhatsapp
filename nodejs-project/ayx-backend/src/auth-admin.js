// Admin authentication middleware.
// Session id comes from the HttpOnly `ayx_admin` cookie and is looked up
// server-side on EVERY request (revocation/expiry take effect immediately).
// State-changing methods additionally require the `X-CSRF-Token` header to
// match the token issued at login. Hiding UI buttons is not security — this
// middleware is the enforcement point.

import { get } from './db.js';
import { config } from './config.js';

const MUTATING = new Set(['POST', 'PATCH', 'PUT', 'DELETE']);

function unauthenticated(res) {
  return res.status(401).json({
    error: { code: 'unauthenticated', message: 'Admin authentication required.' },
  });
}

export async function adminAuth(req, res, next) {
  try {
    const sessionId = req.cookies?.[config.adminCookieName];
    if (!sessionId || typeof sessionId !== 'string') return unauthenticated(res);

    // Server-side session lookup (single connection; revocation/expiry
    // take effect immediately). Join the owning admin in one query.
    const session = get(
      'admin_sessions',
      `SELECT s.*, u.id AS u_id, u.username AS u_username,
              u.password_hash AS u_password_hash, u.totp_secret AS u_totp_secret,
              u.totp_enabled AS u_totp_enabled, u.disabled AS u_disabled,
              u.last_login_at AS u_last_login_at, u.created_at AS u_created_at,
              u.updated_at AS u_updated_at
         FROM admin_sessions s
         JOIN admin_users u ON u.id = s.admin_user_id
        WHERE s.id = ?`,
      [sessionId]
    );

    const adminUser = session
      ? {
          id: session.uId,
          username: session.uUsername,
          passwordHash: session.uPasswordHash,
          totpSecret: session.uTotpSecret,
          totpEnabled: session.uTotpEnabled === 1,
          disabled: session.uDisabled === 1,
          lastLoginAt: session.uLastLoginAt ? new Date(session.uLastLoginAt) : null,
          createdAt: new Date(session.uCreatedAt),
          updatedAt: new Date(session.uUpdatedAt),
        }
      : null;

    const now = new Date();
    if (
      !session ||
      session.revokedAt ||
      session.expiresAt <= now ||
      !adminUser ||
      adminUser.disabled
    ) {
      return unauthenticated(res);
    }

    if (MUTATING.has(req.method)) {
      const csrf = req.headers['x-csrf-token'];
      if (!csrf || typeof csrf !== 'string' || csrf !== session.csrfToken) {
        return res.status(403).json({
          error: { code: 'csrf_invalid', message: 'Missing or invalid CSRF token.' },
        });
      }
    }

    // Strip the password hash before attaching to the request.
    const { passwordHash: _ph, totpSecret: _ts, ...admin } = adminUser;
    req.admin = admin;
    req.adminSession = session;
    next();
  } catch (err) {
    next(err);
  }
}
