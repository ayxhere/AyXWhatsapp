// Database migration runner (SQLite).
//
// Applies versioned SQL files from db/migrations/ in filename order, tracking
// applied versions in the `_migrations` table. Safe to re-run: already-applied
// migrations are skipped. Each migration runs inside its own transaction.
//
// Usage:
//   node db/migrate.js            # migrate the configured DB_PATH
//   import { runMigrations } from './db/migrate.js';

import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getDb } from '../src/db.js';

const MIGRATIONS_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'migrations');

function splitStatements(sql) {
  // Our migration files contain plain DDL/DML only (no triggers, no
  // semicolons inside statements), so splitting on semicolons is safe.
  return sql
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('--'))
    .join('\n')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean);
}

export async function runMigrations() {
  const db = getDb();
  db.exec(
    `CREATE TABLE IF NOT EXISTS _migrations (
       name TEXT PRIMARY KEY,
       applied_at TEXT NOT NULL
     )`
  );
  const applied = new Set(
    db.prepare('SELECT name FROM _migrations').all().map((r) => r.name)
  );
  const files = readdirSync(MIGRATIONS_DIR)
    .filter((f) => f.endsWith('.sql'))
    .sort();

  for (const file of files) {
    const name = file.replace(/\.sql$/, '');
    if (applied.has(name)) continue;
    const sql = readFileSync(path.join(MIGRATIONS_DIR, file), 'utf8');
    db.exec('BEGIN IMMEDIATE');
    try {
      for (const stmt of splitStatements(sql)) db.exec(stmt);
      db.prepare('INSERT INTO _migrations (name, applied_at) VALUES (?, ?)').run(
        name,
        new Date().toISOString()
      );
      db.exec('COMMIT');
      console.log(`[migrate] applied ${name}`);
    } catch (err) {
      try {
        db.exec('ROLLBACK');
      } catch {
        /* ignore */
      }
      throw new Error(`[migrate] ${name} failed: ${err.message}`);
    }
  }
}

const invokedDirectly =
  process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  runMigrations()
    .then(() => process.exit(0))
    .catch((err) => {
      console.error(err.message);
      process.exit(1);
    });
}
