// Installation authentication middleware.
// Clients send `Authorization: Bearer <access_token>`.
// The server stores only SHA-256(token); the raw token never touches the DB.
// Revoked installations are rejected with code `installation_revoked`.

import { createHash, timingSafeEqual } from 'node:crypto';
import { get } from './db.js';

export function hashToken(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

// Constant-time comparison for fixed-length hex digests.
export function safeEqualHex(a, b) {
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

export async function installationAuth(req, res, next) {
  try {
    const header = req.headers.authorization;
    if (!header || !header.toLowerCase().startsWith('bearer ')) {
      return res.status(401).json({
        error: { code: 'unauthorized', message: 'Missing bearer credentials.' },
      });
    }
    const token = header.slice(7).trim();
    if (!token) {
      return res.status(401).json({
        error: { code: 'unauthorized', message: 'Missing bearer credentials.' },
      });
    }

    const installation = get('installations', 'SELECT * FROM installations WHERE token_hash = ?', [
      hashToken(token),
    ]);

    // Generic "not found" first: never reveal whether a token exists.
    if (!installation) {
      return res.status(401).json({
        error: { code: 'invalid_token', message: 'Invalid credentials.' },
      });
    }
    if (installation.revoked) {
      return res.status(401).json({
        error: { code: 'installation_revoked', message: 'Installation has been revoked.' },
      });
    }
    // Blocked (abuse) installations are rejected on every installation
    // endpoint — checked here, next to the revocation check.
    if (installation.blocked) {
      return res.status(403).json({
        error: { code: 'installation_blocked', message: 'Installation has been blocked.' },
      });
    }

    req.installation = installation;
    next();
  } catch (err) {
    next(err);
  }
}
