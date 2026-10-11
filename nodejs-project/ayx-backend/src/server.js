// Production entrypoint: `npm start`.

async function main() {
  // node:sqlite ships inside Node.js itself (no native modules). If it cannot
  // be loaded, this Node build is too old / incompatible for the AyX backend.
  try {
    process.getBuiltinModule('node:sqlite');
  } catch {
    console.error(
      '[boot] fatal: node:sqlite is unavailable — install an Android-compatible ' +
        'Node.js v22.13+ or v23+ build in IMAYX Host → Settings → Node Runtime.'
    );
    process.exit(1);
  }

  const [{ createApp }, { config }, { runMigrations }, { run }, { initFcm }, { startMaintenanceLoop }] =
    await Promise.all([
      import('./app.js'),
      import('./config.js'),
      import('../db/migrate.js'),
      import('./db.js'),
      import('./services/push.js'),
      import('./services/cleanup.js'),
    ]);

  // Apply any pending migrations (idempotent — safe to run on every boot).
  await runMigrations();

  // Fail fast if the database is unusable.
  await run('SELECT 1');

  await initFcm();
  startMaintenanceLoop();

  const app = await createApp();
  app.listen(config.port, () => {
    console.log(`[ayx-group-api] listening on :${config.port} (env=${config.nodeEnv})`);
  });
}

main().catch((err) => {
  console.error('[boot] fatal:', err.message);
  process.exit(1);
});
