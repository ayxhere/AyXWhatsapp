// Local operations — one-time admin bootstrap for embedded deployments.
// Guarded by LOCAL_OP_TOKEN (a per-device secret, never exposed publicly).
// Only binds to 127.0.0.1 via the app; not reachable through the tunnel.

import { Router } from 'express';
import bcrypt from 'bcryptjs';
import { get, insertRow, newId, nowIso } from '../db.js';

const router = Router();

function checkToken(req, res, next) {
  const expected = process.env.LOCAL_OP_TOKEN || '';
  const got = req.get('X-Local-Token') || '';
  if (!expected || got !== expected) {
    return res.status(403).json({ ok: false, error: 'forbidden' });
  }
  next();
}

// POST /v1/local/admin/bootstrap { username, password }
// One-time only: refuses if any admin already exists.
router.post('/admin/bootstrap', checkToken, async (req, res, next) => {
  try {
    const existing = get('SELECT COUNT(*) AS c FROM admin_users').c;
    if (existing > 0) {
      return res.status(409).json({ ok: false, error: 'admin already exists' });
    }
    const username = String(req.body?.username || '');
    const password = String(req.body?.password || '');
    if (!/^[A-Za-z0-9._-]{3,64}$/.test(username)) {
      return res.status(400).json({ ok: false, error: 'username must be 3-64 chars: letters, digits, . _ -' });
    }
    if (password.length < 12) {
      return res.status(400).json({ ok: false, error: 'password must be at least 12 characters' });
    }
    const passwordHash = await bcrypt.hash(password, 12);
    const id = newId();
    const now = nowIso();
    insertRow('admin_users', {
      id,
      username,
      password_hash: passwordHash,
      created_at: now,
      updated_at: now,
    });
    res.json({ ok: true, username });
  } catch (e) {
    next(e);
  }
});

// GET /v1/local/admin/exists — check if bootstrap is needed (no secret data).
router.get('/admin/exists', checkToken, (req, res, next) => {
  try {
    const existing = get('SELECT COUNT(*) AS c FROM admin_users').c;
    res.json({ ok: true, exists: existing > 0 });
  } catch (e) {
    next(e);
  }
});

export default router;
