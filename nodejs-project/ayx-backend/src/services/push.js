// Push delivery via Firebase Cloud Messaging (firebase-admin).
//
// CONTRACT RULE: FCM is used ONLY when FCM_SERVICE_ACCOUNT_JSON points at a
// valid service-account file. Otherwise every job/receipt is recorded as
// `skipped` with reason `fcm_not_configured` and the admin UI shows a
// "Push not configured" badge. Delivery success is NEVER faked.
//
// Private user message bodies are NEVER included in broadcast payloads —
// push jobs only ever reference announcements.

import { readFileSync, existsSync } from 'node:fs';
import { get, all, run, insertRow, transaction, newId, nowIso } from '../db.js';
import { config } from '../config.js';

let adminSdk = null;
let fcmReady = false;
let fcmInitError = null;

export function fcmStatus() {
  return { configured: fcmReady, error: fcmInitError };
}

export function initFcm() {
  const path = config.fcmServiceAccountJson;
  if (!path) {
    fcmInitError = 'FCM_SERVICE_ACCOUNT_JSON not set';
    return;
  }
  if (!existsSync(path)) {
    fcmInitError = `FCM service account file not found: ${path}`;
    console.warn(`[push] ${fcmInitError} — push jobs will be recorded as skipped.`);
    return;
  }
  try {
    const serviceAccount = JSON.parse(readFileSync(path, 'utf8'));
    // Lazy import so the module loads fine without firebase-admin installed.
    return import('firebase-admin').then((mod) => {
      const admin = mod.default ?? mod;
      if (admin.apps.length === 0) {
        admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
      }
      adminSdk = admin;
      fcmReady = true;
      fcmInitError = null;
      console.log('[push] FCM initialized.');
    }).catch((err) => {
      fcmInitError = `firebase-admin load failed: ${err.message}`;
      console.warn(`[push] ${fcmInitError}`);
    });
  } catch (err) {
    fcmInitError = `FCM service account JSON invalid: ${err.message}`;
    console.warn(`[push] ${fcmInitError} — push jobs will be recorded as skipped.`);
  }
}

/**
 * Create a push job for an announcement and fan out per-installation
 * receipts. Dedupe key: (push_job_id, installation_id) — unique constraint.
 * Targets: non-revoked installations that have a registered FCM token.
 */
export async function createPushJob({ announcementId, title, body, deepLink = null, createdBy = null }) {
  const targets = all(
    'push_tokens',
    `SELECT pt.* FROM push_tokens pt
       JOIN installations i ON i.id = pt.installation_id
      WHERE i.revoked = 0`
  );

  const jobId = newId();
  const now = nowIso();
  insertRow('push_jobs', {
    id: jobId,
    type: 'announcement',
    announcement_id: announcementId,
    title: title.slice(0, 120),
    body: body.slice(0, 240),
    deep_link: deepLink,
    status: 'queued',
    total_targets: targets.length,
    created_by: createdBy,
    created_at: now,
  });

  // One receipt per (job, installation) — the unique constraint dedupes.
  if (targets.length > 0) {
    const values = targets.map(() => '(?, ?, ?, ?, ?)').join(', ');
    const params = [];
    for (const t of targets) {
      params.push(announcementId, t.installationId, jobId, 'pending', now);
    }
    // INSERT OR IGNORE == createMany({ skipDuplicates: true }).
    run(
      `INSERT OR IGNORE INTO announcement_receipts
         (announcement_id, installation_id, push_job_id, status, created_at)
       VALUES ${values}`,
      params
    );
  }

  // Process synchronously for deterministic, testable behaviour.
  await processPushJob(jobId);
  return get('push_jobs', 'SELECT * FROM push_jobs WHERE id = ?', [jobId]);
}

async function markSkipped(jobId, announcementId, reason) {
  const now = nowIso();
  await transaction(() => {
    run('UPDATE push_jobs SET status = ?, started_at = ?, finished_at = ?, error = ? WHERE id = ?', [
      'skipped',
      now,
      now,
      reason,
      jobId,
    ]);
    run('UPDATE announcement_receipts SET status = ?, reason = ? WHERE push_job_id = ?', [
      'skipped',
      reason,
      jobId,
    ]);
  });
  const row = get(
    null,
    'SELECT COUNT(*) AS c FROM announcement_receipts WHERE push_job_id = ? AND status = ?',
    [jobId, 'skipped']
  );
  run('UPDATE push_jobs SET skipped_count = ? WHERE id = ?', [row.c, jobId]);
  void announcementId;
}

export async function processPushJob(jobId) {
  const job = get('push_jobs', 'SELECT * FROM push_jobs WHERE id = ?', [jobId]);
  if (!job || job.status !== 'queued') return;

  run('UPDATE push_jobs SET status = ?, started_at = ? WHERE id = ?', ['running', nowIso(), jobId]);

  // No FCM configured -> record honestly as skipped, never as sent.
  if (!fcmReady || !adminSdk) {
    await markSkipped(jobId, job.announcementId, 'fcm_not_configured');
    return;
  }

  try {
    // Receipts with their installation's current FCM token (if any).
    const receipts = all(
      null,
      `SELECT r.id AS rid, pt.fcm_token AS token
         FROM announcement_receipts r
         LEFT JOIN push_tokens pt ON pt.installation_id = r.installation_id
        WHERE r.push_job_id = ? AND r.status = 'pending'`,
      [jobId]
    ).map((r) => ({
      id: r.rid,
      installation: r.token ? { pushToken: { fcmToken: r.token } } : null,
    }));

    const messaging = adminSdk.messaging();
    let sent = 0;
    let failed = 0;

    // Only installations that still have a token can be targeted.
    const targetable = receipts.filter((r) => r.installation?.pushToken?.fcmToken);

    // FCM multicast limit is 500 tokens per call.
    for (let i = 0; i < targetable.length; i += 500) {
      const batch = targetable.slice(i, i + 500);
      const tokens = batch.map((r) => r.installation.pushToken.fcmToken);
      const resp = await messaging.sendEachForMulticast({
        tokens,
        notification: { title: job.title, body: job.body },
        data: {
          type: 'announcement',
          announcement_id: job.announcementId ?? '',
          deep_link: job.deepLink ?? 'ayxgroup://announcements',
        },
        android: { priority: 'high' },
      });
      for (let j = 0; j < batch.length; j++) {
        const r = batch[j];
        const ok = resp.responses[j]?.success;
        if (ok) {
          sent++;
          run('UPDATE announcement_receipts SET status = ?, sent_at = ? WHERE id = ?', [
            'sent',
            nowIso(),
            r.id,
          ]);
        } else {
          failed++;
          const errMsg = String(resp.responses[j]?.error?.message ?? 'fcm_error').slice(0, 255);
          run('UPDATE announcement_receipts SET status = ?, reason = ? WHERE id = ?', [
            'failed',
            errMsg,
            r.id,
          ]);
        }
      }
    }

    // Receipts whose installation lost its token meanwhile.
    const noTokenRow = get(
      null,
      'SELECT COUNT(*) AS c FROM announcement_receipts WHERE push_job_id = ? AND status = ?',
      [jobId, 'pending']
    );
    const noToken = noTokenRow.c;
    if (noToken > 0) {
      run(
        'UPDATE announcement_receipts SET status = ?, reason = ? WHERE push_job_id = ? AND status = ?',
        ['skipped', 'no_push_token', jobId, 'pending']
      );
    }

    run(
      `UPDATE push_jobs
          SET status = ?, sent_count = ?, failed_count = ?, skipped_count = ?, finished_at = ?
        WHERE id = ?`,
      [failed > 0 && sent === 0 ? 'failed' : 'completed', sent, failed, noToken, nowIso(), jobId]
    );
  } catch (err) {
    run('UPDATE push_jobs SET status = ?, finished_at = ?, error = ? WHERE id = ?', [
      'failed',
      nowIso(),
      String(err.message).slice(0, 2000),
      jobId,
    ]);
  }
}
