-- AyX Group initial schema (SQLite)
-- Migration: 001_init
-- Faithful port of the original MySQL 001_init migration.
-- Conventions: DateTime -> TEXT (ISO8601 UTC, lexicographically sortable),
-- booleans -> INTEGER 0/1, ENUM -> TEXT + CHECK, JSON -> TEXT,
-- AUTO_INCREMENT -> INTEGER PRIMARY KEY AUTOINCREMENT.

CREATE TABLE admin_users (
  id TEXT NOT NULL PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  totp_secret TEXT NULL,
  disabled INTEGER NOT NULL DEFAULT 0,
  last_login_at TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE admin_sessions (
  id TEXT NOT NULL PRIMARY KEY,
  admin_user_id TEXT NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
  csrf_token TEXT NOT NULL,
  ip_trunc TEXT NULL,
  user_agent TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  expires_at TEXT NOT NULL,
  revoked_at TEXT NULL
);
CREATE INDEX admin_sessions_admin_user_id_idx ON admin_sessions(admin_user_id);
CREATE INDEX admin_sessions_expires_at_idx ON admin_sessions(expires_at);

CREATE TABLE installations (
  id TEXT NOT NULL PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  app_version TEXT NULL,
  platform TEXT NULL,
  locale TEXT NULL,
  last_sync_at TEXT NULL,
  last_reply_seen_at TEXT NULL,
  revoked INTEGER NOT NULL DEFAULT 0,
  revoked_at TEXT NULL,
  revoked_reason TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX installations_revoked_idx ON installations(revoked);
CREATE INDEX installations_last_sync_at_idx ON installations(last_sync_at);

CREATE TABLE push_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  installation_id TEXT NOT NULL UNIQUE REFERENCES installations(id) ON DELETE CASCADE ON UPDATE CASCADE,
  fcm_token TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE announcements (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','scheduled','published','archived')),
  scheduled_at TEXT NULL,
  published_at TEXT NULL,
  notify_pending INTEGER NOT NULL DEFAULT 0,
  push_title TEXT NULL,
  push_body TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX announcements_status_idx ON announcements(status);
CREATE INDEX announcements_published_at_idx ON announcements(published_at);
CREATE INDEX announcements_scheduled_at_idx ON announcements(scheduled_at);

CREATE TABLE media_assets (
  id TEXT NOT NULL PRIMARY KEY,
  filename TEXT NOT NULL,
  original_name TEXT NOT NULL,
  mime TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  created_by TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX media_assets_sha256_idx ON media_assets(sha256);
CREATE INDEX media_assets_created_at_idx ON media_assets(created_at);

CREATE TABLE statuses (
  id TEXT NOT NULL PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('text','image','video')),
  text TEXT NULL,
  media_id TEXT NULL REFERENCES media_assets(id) ON DELETE SET NULL ON UPDATE CASCADE,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX statuses_expires_at_idx ON statuses(expires_at);
CREATE INDEX statuses_media_id_idx ON statuses(media_id);

CREATE TABLE user_messages (
  id TEXT NOT NULL PRIMARY KEY,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE ON UPDATE CASCADE,
  body TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('feedback','bug','other')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','resolved','spam')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX user_messages_installation_id_created_at_idx ON user_messages(installation_id, created_at);
CREATE INDEX user_messages_status_idx ON user_messages(status);
CREATE INDEX user_messages_created_at_idx ON user_messages(created_at);

CREATE TABLE admin_replies (
  id TEXT NOT NULL PRIMARY KEY,
  message_id TEXT NOT NULL REFERENCES user_messages(id) ON DELETE CASCADE ON UPDATE CASCADE,
  admin_user_id TEXT NULL REFERENCES admin_users(id) ON DELETE SET NULL ON UPDATE CASCADE,
  body TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX admin_replies_message_id_idx ON admin_replies(message_id);
CREATE INDEX admin_replies_created_at_idx ON admin_replies(created_at);

CREATE TABLE sync_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  entity TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  installation_id TEXT NULL REFERENCES installations(id) ON DELETE CASCADE ON UPDATE CASCADE,
  payload TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX sync_events_installation_id_id_idx ON sync_events(installation_id, id);
CREATE INDEX sync_events_created_at_idx ON sync_events(created_at);

CREATE TABLE device_cursors (
  installation_id TEXT NOT NULL PRIMARY KEY REFERENCES installations(id) ON DELETE CASCADE ON UPDATE CASCADE,
  last_event_id INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE push_jobs (
  id TEXT NOT NULL PRIMARY KEY,
  type TEXT NOT NULL DEFAULT 'announcement',
  announcement_id TEXT NULL REFERENCES announcements(id) ON DELETE SET NULL ON UPDATE CASCADE,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  deep_link TEXT NULL,
  status TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','completed','failed','skipped')),
  total_targets INTEGER NOT NULL DEFAULT 0,
  sent_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  skipped_count INTEGER NOT NULL DEFAULT 0,
  scheduled_at TEXT NULL,
  started_at TEXT NULL,
  finished_at TEXT NULL,
  error TEXT NULL,
  created_by TEXT NULL REFERENCES admin_users(id) ON DELETE SET NULL ON UPDATE CASCADE,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX push_jobs_status_idx ON push_jobs(status);
CREATE INDEX push_jobs_scheduled_at_idx ON push_jobs(scheduled_at);
CREATE INDEX push_jobs_created_at_idx ON push_jobs(created_at);

CREATE TABLE announcement_receipts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE ON UPDATE CASCADE,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE ON UPDATE CASCADE,
  push_job_id TEXT NULL REFERENCES push_jobs(id) ON DELETE SET NULL ON UPDATE CASCADE,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','sent','failed','skipped')),
  reason TEXT NULL,
  sent_at TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE (push_job_id, installation_id)
);
CREATE INDEX announcement_receipts_announcement_id_idx ON announcement_receipts(announcement_id);
CREATE INDEX announcement_receipts_installation_id_idx ON announcement_receipts(installation_id);
CREATE INDEX announcement_receipts_status_idx ON announcement_receipts(status);
CREATE INDEX announcement_receipts_created_at_idx ON announcement_receipts(created_at);

CREATE TABLE audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  admin_user_id TEXT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NULL,
  result TEXT NOT NULL CHECK (result IN ('success','failure')),
  ip_trunc TEXT NULL,
  meta TEXT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX audit_logs_created_at_idx ON audit_logs(created_at);
CREATE INDEX audit_logs_admin_user_id_idx ON audit_logs(admin_user_id);
CREATE INDEX audit_logs_action_idx ON audit_logs(action);
