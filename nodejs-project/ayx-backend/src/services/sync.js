// Sync event log (append-only).
// `sync_events.id` is the monotonically increasing cursor clients use with
// GET /v1/sync?cursor=. Events are either global (installationId = null,
// e.g. announcements/statuses) or private (installationId set, e.g. a user's
// own messages) — private events are only ever served to their owner.

import { get, insertRow, nowIso } from '../db.js';

export async function emitEvent({ kind, entity, entityId, installationId = null, payload = null }) {
  // Append-only event log; id is AUTOINCREMENT (monotonic cursor).
  const res = insertRow('sync_events', {
    kind,
    entity,
    entity_id: String(entityId),
    installation_id: installationId,
    payload: payload == null ? null : JSON.stringify(payload),
    created_at: nowIso(),
  });
  return res.lastInsertRowid;
}

export async function maxEventId() {
  const row = get(null, 'SELECT id FROM sync_events ORDER BY id DESC LIMIT 1');
  return row ? row.id : 0;
}

// Serialize helpers shared by bootstrap + sync responses.
export function serializeAnnouncement(a) {
  return {
    id: a.id,
    title: a.title,
    body: a.body,
    status: a.status,
    published_at: a.publishedAt,
    scheduled_at: a.scheduledAt,
    created_at: a.createdAt,
    updated_at: a.updatedAt,
  };
}

export function serializeStatus(s, apiPublicBase) {
  return {
    id: s.id,
    kind: s.kind,
    status: s.status,
    text: s.text,
    media_id: s.mediaId,
    media_url: s.mediaId ? `${apiPublicBase}/v1/media/${s.mediaId}` : null,
    expires_at: s.expiresAt,
    published_at: s.publishedAt,
    created_at: s.createdAt,
  };
}

export function serializeMessage(m) {
  return {
    id: m.id,
    body: m.body,
    kind: m.kind,
    status: m.status,
    created_at: m.createdAt,
    replies: (m.replies ?? []).map((r) => ({
      id: r.id,
      body: r.body,
      created_at: r.createdAt,
    })),
  };
}
