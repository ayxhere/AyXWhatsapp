-- AyX Group hardening (SQLite)
-- Migration: 002_hardening
-- Faithful port of the original MySQL 002_hardening migration.
-- Applies AFTER 001_init. Purely additive except the statuses backfill.

ALTER TABLE admin_users ADD COLUMN totp_enabled INTEGER NOT NULL DEFAULT 0;

-- Brute-force lockout state, keyed on the ATTEMPTED username (uniform for
-- unknown usernames too, so lockout responses never reveal account existence).
CREATE TABLE admin_lockouts (
  username TEXT NOT NULL PRIMARY KEY,
  failures INTEGER NOT NULL DEFAULT 0,
  locked_until TEXT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- Short-lived single-use TOTP login challenges (5 min). Only the SHA-256 of
-- the challenge token handed to the client is stored.
CREATE TABLE admin_totp_challenges (
  id TEXT NOT NULL PRIMARY KEY,
  admin_user_id TEXT NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
  challenge_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX admin_totp_challenges_admin_user_id_idx ON admin_totp_challenges(admin_user_id);

ALTER TABLE installations ADD COLUMN last_seen_at TEXT NULL;
ALTER TABLE installations ADD COLUMN display_label TEXT NULL;
ALTER TABLE installations ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE installations ADD COLUMN blocked_reason TEXT NULL;
ALTER TABLE installations ADD COLUMN blocked_at TEXT NULL;
CREATE INDEX installations_blocked_idx ON installations(blocked);
CREATE INDEX installations_last_seen_at_idx ON installations(last_seen_at);

ALTER TABLE user_messages ADD COLUMN read_at TEXT NULL;
ALTER TABLE user_messages ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;
CREATE INDEX user_messages_archived_idx ON user_messages(archived);

ALTER TABLE statuses ADD COLUMN status TEXT NOT NULL DEFAULT 'published'
  CHECK (status IN ('draft','published','archived'));
ALTER TABLE statuses ADD COLUMN published_at TEXT NULL;
CREATE INDEX statuses_status_idx ON statuses(status);

-- Backfill: every pre-existing status was implicitly published at creation.
UPDATE statuses SET published_at = created_at WHERE published_at IS NULL;
