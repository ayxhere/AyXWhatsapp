// Audit logging. Every sensitive admin action is recorded with actor,
// action, target, result and a truncated IP. Failures to write the audit log
// must never break the request path, so errors are swallowed after a
// server-side console.error.

import { insertRow, nowIso } from '../db.js';

export async function auditLog({ adminUserId = null, actor, action, target = null, result, ip = null, meta = null }) {
  try {
    insertRow('audit_logs', {
      admin_user_id: adminUserId,
      actor: String(actor).slice(0, 64),
      action: String(action).slice(0, 64),
      target: target ? String(target).slice(0, 128) : null,
      result,
      ip_trunc: ip,
      meta: meta == null ? null : JSON.stringify(meta),
      created_at: nowIso(),
    });
  } catch (err) {
    console.error('[audit] failed to write audit log:', err.message);
  }
}
