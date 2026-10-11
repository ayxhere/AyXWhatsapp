// Central environment configuration.
// SECURITY: fails fast at boot when a required variable is missing so the
// service never runs in a half-configured state. Real secrets come ONLY from
// the environment — never from code, the repo, or .env.example.

import dotenv from 'dotenv';

dotenv.config();

function required(name, { minLength = 1 } = {}) {
  const v = process.env[name];
  if (!v || v.length < minLength) {
    throw new Error(
      `[config] Missing or invalid required environment variable: ${name}. ` +
        `Copy .env.example to .env and fill in a real value.`
    );
  }
  return v;
}

function optional(name, fallback) {
  const v = process.env[name];
  return v === undefined || v === '' ? fallback : v;
}

function int(name, fallback) {
  const raw = optional(name, undefined);
  if (raw === undefined) return fallback;
  const n = Number.parseInt(raw, 10);
  if (!Number.isSafeInteger(n) || n <= 0) {
    throw new Error(`[config] ${name} must be a positive integer, got: ${raw}`);
  }
  return n;
}

export const config = {
  nodeEnv: optional('NODE_ENV', 'development'),
  get isProd() {
    return this.nodeEnv === 'production';
  },

  port: int('PORT', 3101),

  // --- database ----------------------------------------------------------
  // SQLite file location. Empty = default ./data/ayx.db under the project dir
  // (resolved in src/db.js). No database server needed — node:sqlite only.
  dbPath: optional('DB_PATH', ''),

  // --- required secrets -------------------------------------------------
  // Used to sign nothing directly here, but required as a canary that the
  // operator provisioned server-side secrets; also used by create-admin flows.
  sessionSecret: required('SESSION_SECRET', { minLength: 32 }),

  // --- admin auth --------------------------------------------------------
  adminCookieName: optional('ADMIN_COOKIE_NAME', 'ayx_admin'),
  adminSessionHours: int('ADMIN_SESSION_HOURS', 12),

  // --- CORS ---------------------------------------------------------------
  corsOrigins: optional(
    'CORS_ORIGINS',
    'https://imayx.in,https://hello.imayx.in'
  )
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),

  // Public base URL of this API, used to build absolute media URLs.
  apiPublicBase: optional('API_PUBLIC_BASE', 'https://api.imayx.in').replace(/\/$/, ''),

  // --- uploads -------------------------------------------------------------
  uploadDir: optional('UPLOAD_DIR', './uploads'),
  maxUploadMb: int('MAX_UPLOAD_MB', 25),

  // --- push (optional) ------------------------------------------------------
  // Path to a Firebase service-account JSON file. When absent, the push
  // subsystem records jobs as `skipped` (reason: fcm_not_configured) and the
  // admin UI must show "Push not configured". Delivery is NEVER faked.
  fcmServiceAccountJson: optional('FCM_SERVICE_ACCOUNT_JSON', ''),

  // --- initial admin bootstrap ----------------------------------------------
  adminSetupUser: optional('ADMIN_SETUP_USER', ''),
  adminSetupPass: optional('ADMIN_SETUP_PASS', ''),
};
