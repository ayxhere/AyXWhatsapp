// Periodic maintenance jobs: session expiry, scheduled-announcement
// publishing, orphan media cleanup. Started from server.js on an interval;
// each job is idempotent and safe to run concurrently (best-effort).

import { run, get, all, insertRow, deleteById, updateById, nowIso } from '../db.js';
import { emitEvent } from './sync.js';
import { createPushJob } from './push.js';
import { cleanupOrphanMedia } from './media.js';
import { auditLog } from './audit.js';

export async function expireSessions() {
  const now = nowIso();
  // Hard-delete sessions expired more than 7 days ago; soft-revoke the rest.
  const stale = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);
  run('DELETE FROM admin_sessions WHERE expires_at < ? OR revoked_at < ?', [stale, stale]);
  run('UPDATE admin_sessions SET revoked_at = ? WHERE expires_at < ? AND revoked_at IS NULL', [now, now]);
  // TOTP challenges are single-use and 5-minute lived; purge leftovers.
  run('DELETE FROM admin_totp_challenges WHERE expires_at < ?', [now]);
}

export async function publishDueScheduled() {
  const now = nowIso();
  const due = all('announcements', 'SELECT * FROM announcements WHERE status = ? AND scheduled_at <= ?', [
    'scheduled',
    now,
  ]);
  for (const a of due) {
    updateById('announcements', a.id, { status: 'published', published_at: now, updated_at: now });
    await emitEvent({
      kind: 'announcement.upsert',
      entity: 'announcement',
      entityId: a.id,
    });
    if (a.notifyPending) {
      await createPushJob({
        announcementId: a.id,
        title: a.pushTitle || a.title,
        body: a.pushBody || a.body.slice(0, 240),
        createdBy: null,
      });
      updateById('announcements', a.id, { notify_pending: false, updated_at: nowIso() });
    }
    await auditLog({
      actor: 'system',
      action: 'announcement.scheduled_publish',
      target: a.id,
      result: 'success',
    });
  }
  return due.length;
}

export async function runMaintenance() {
  try {
    await expireSessions();
  } catch (e) {
    console.error('[maintenance] expireSessions failed:', e.message);
  }
  try {
    await publishDueScheduled();
  } catch (e) {
    console.error('[maintenance] publishDueScheduled failed:', e.message);
  }
  try {
    const removed = await cleanupOrphanMedia();
    if (removed > 0) console.log(`[maintenance] removed ${removed} orphan media assets`);
  } catch (e) {
    console.error('[maintenance] cleanupOrphanMedia failed:', e.message);
  }
}

export function startMaintenanceLoop() {
  // Run once at boot, then every 5 minutes.
  runMaintenance();
  const timer = setInterval(runMaintenance, 5 * 60 * 1000);
  timer.unref?.();
  return timer;
}
