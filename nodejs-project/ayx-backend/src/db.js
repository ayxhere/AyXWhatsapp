// SQLite data layer (node:sqlite — built into Node.js, zero native deps).
//
// Conventions (must match db/migrations/*.sql):
//   - DateTime columns are TEXT holding ISO8601 UTC ("2026-10-11T00:31:21.123Z").
//     Fixed-width UTC strings sort lexicographically == chronologically, so
//     SQL comparisons (>, <, ORDER BY) behave like datetime comparisons.
//   - Booleans are INTEGER 0/1.
//   - JSON columns (sync_events.payload, audit_logs.meta) are TEXT.
//
// The row mapper below converts snake_case columns back to camelCase
// properties with proper JS types (Date / boolean / parsed object), so rows
// arrive in the shape the rest of the codebase expects. ALL queries are
// parameterized — never interpolate values into SQL strings.

import { mkdirSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Loaded via process.getBuiltinModule instead of a static `node:sqlite`
// import: vitest 2.x / vite 5 mis-resolve the `node:sqlite` specifier at
// transform time. getBuiltinModule exists since Node 22.3; engines require
// >= 22.13. Throws here with a clear message when unavailable.
let DatabaseSync;
try {
  ({ DatabaseSync } = process.getBuiltinModule('node:sqlite'));
} catch {
  throw new Error(
    '[db] node:sqlite is unavailable — install an Android-compatible ' +
      'Node.js v22.13+ or v23+ build in IMAYX Host → Settings → Node Runtime.'
  );
}

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function resolveDbPath() {
  const raw = process.env.DB_PATH;
  let p;
  if (raw && raw.trim()) {
    // Explicit path: absolute stays absolute, relative resolves from CWD.
    p = path.isAbsolute(raw) ? raw : path.resolve(process.cwd(), raw);
  } else {
    // Default: ./data/ayx.db under the project dir.
    p = path.join(PROJECT_ROOT, 'data', 'ayx.db');
  }
  mkdirSync(path.dirname(p), { recursive: true });
  return p;
}

// Per-table column type metadata (snake_case names as stored in SQLite).
const TABLES = {
  admin_users: {
    bool: ['totp_enabled', 'disabled'],
    date: ['last_login_at', 'created_at', 'updated_at'],
  },
  admin_lockouts: { bool: [], date: ['locked_until', 'updated_at'] },
  admin_totp_challenges: { bool: [], date: ['expires_at', 'created_at'] },
  admin_sessions: { bool: [], date: ['created_at', 'expires_at', 'revoked_at'] },
  installations: {
    bool: ['blocked', 'revoked'],
    date: [
      'last_sync_at',
      'last_reply_seen_at',
      'last_seen_at',
      'blocked_at',
      'revoked_at',
      'created_at',
      'updated_at',
    ],
  },
  push_tokens: { bool: [], date: ['created_at', 'updated_at'] },
  announcements: {
    bool: ['notify_pending'],
    date: ['scheduled_at', 'published_at', 'created_at', 'updated_at'],
  },
  announcement_receipts: { bool: [], date: ['sent_at', 'created_at'] },
  statuses: { bool: [], date: ['expires_at', 'published_at', 'created_at', 'updated_at'] },
  media_assets: { bool: [], date: ['created_at'] },
  user_messages: { bool: ['archived'], date: ['read_at', 'created_at', 'updated_at'] },
  admin_replies: { bool: [], date: ['created_at'] },
  sync_events: { bool: [], date: ['created_at'], json: ['payload'] },
  device_cursors: { bool: [], date: ['updated_at'] },
  push_jobs: {
    bool: [],
    date: ['scheduled_at', 'started_at', 'finished_at', 'created_at'],
  },
  audit_logs: { bool: [], date: ['created_at'], json: ['meta'] },
};

function snakeToCamel(s) {
  return s.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
}

function mapRow(table, row) {
  if (row == null) return null;
  const meta = (table && TABLES[table]) || { bool: [], date: [], json: [] };
  const out = {};
  for (const [key, value] of Object.entries(row)) {
    const camel = snakeToCamel(key);
    if (value === null || value === undefined) {
      out[camel] = null;
      continue;
    }
    if (meta.bool?.includes(key)) out[camel] = value === 1 || value === true;
    else if (meta.date?.includes(key)) out[camel] = new Date(value);
    else if (meta.json?.includes(key)) {
      try {
        out[camel] = JSON.parse(value);
      } catch {
        out[camel] = null;
      }
    } else out[camel] = value;
  }
  return out;
}

let db = null;

export function getDb() {
  if (!db) {
    db = new DatabaseSync(resolveDbPath());
    db.exec('PRAGMA journal_mode = WAL');
    db.exec('PRAGMA busy_timeout = 5000');
    db.exec('PRAGMA foreign_keys = ON');
    db.exec('PRAGMA synchronous = NORMAL');
  }
  return db;
}

function normParam(p) {
  if (p === undefined) return null;
  if (p instanceof Date) return Number.isNaN(p.getTime()) ? null : p.toISOString();
  if (typeof p === 'boolean') return p ? 1 : 0;
  return p;
}

const normParams = (params) => (params ?? []).map(normParam);

/** One row (or null). `table` selects the row mapper; pass null for raw rows. */
export function get(table, sql, params) {
  const row = getDb().prepare(sql).get(...normParams(params));
  return mapRow(table, row ?? null);
}

/** All rows. `table` selects the row mapper; pass null for raw rows. */
export function all(table, sql, params) {
  const rows = getDb().prepare(sql).all(...normParams(params));
  return rows.map((r) => mapRow(table, r));
}

/** INSERT/UPDATE/DELETE. Returns { changes, lastInsertRowid }. */
export function run(sql, params) {
  const res = getDb().prepare(sql).run(...normParams(params));
  return { changes: Number(res.changes), lastInsertRowid: Number(res.lastInsertRowid) };
}

/** Raw exec (DDL, pragmas). */
export function exec(sql) {
  getDb().exec(sql);
}

/**
 * Run fn() inside a transaction (BEGIN IMMEDIATE … COMMIT/ROLLBACK).
 * All helpers share the single connection, so nested calls compose naturally.
 */
export async function transaction(fn) {
  const conn = getDb();
  conn.exec('BEGIN IMMEDIATE');
  try {
    const out = await fn();
    conn.exec('COMMIT');
    return out;
  } catch (err) {
    try {
      conn.exec('ROLLBACK');
    } catch {
      /* ignore */
    }
    throw err;
  }
}

/** INSERT a row from a { snake_case_col: value } object. */
export function insertRow(table, data) {
  const cols = Object.keys(data);
  const placeholders = cols.map(() => '?').join(', ');
  return run(
    `INSERT INTO ${table} (${cols.join(', ')}) VALUES (${placeholders})`,
    Object.values(data)
  );
}

/** UPDATE one row by primary-key `id`; returns the fresh row (or null). */
export function updateById(table, id, data) {
  const cols = Object.keys(data);
  const set = cols.map((c) => `${c} = ?`).join(', ');
  run(`UPDATE ${table} SET ${set} WHERE id = ?`, [...Object.values(data), id]);
  return get(table, `SELECT * FROM ${table} WHERE id = ?`, [id]);
}

/** DELETE one row by primary-key `id`; returns number of rows deleted. */
export function deleteById(table, id) {
  return run(`DELETE FROM ${table} WHERE id = ?`, [id]).changes;
}

/** Escape LIKE wildcards so `contains` behaves as a literal substring search. */
export function likeEscape(s) {
  return String(s).replace(/[\\%_]/g, (c) => `\\${c}`);
}

/** Random 128-bit hex id for TEXT primary keys. */
export function newId() {
  return randomBytes(16).toString('hex');
}

/** Current time as an ISO8601 UTC string (matches the TEXT datetime format). */
export function nowIso() {
  return new Date().toISOString();
}

export function closeDb() {
  try {
    db?.close();
  } catch {
    /* ignore */
  }
  db = null;
}
