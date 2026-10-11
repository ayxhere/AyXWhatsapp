// AyX Group backend boot wrapper for embedded Node (inside AyX WhatsApp).
//
// Unlike src/server.js (which calls process.exit on fatal errors — that would
// kill the Baileys gateway too), this wrapper isolates backend failures:
// the gateway keeps running even if the backend fails to start.

export async function startAyxBackend() {
  // node:sqlite ships inside Node.js itself. The embedded libnode is v24, so
  // this should always be available. If not, log and return (don't kill gateway).
  try {
    process.getBuiltinModule('node:sqlite');
  } catch {
    console.error('[ayx-backend] node:sqlite unavailable — backend disabled, gateway unaffected');
    return false;
  }

  try {
    const [{ createApp }, { config }, { runMigrations }, { run }, { initFcm }, { startMaintenanceLoop }] =
      await Promise.all([
        import('./src/app.js'),
        import('./src/config.js'),
        import('./db/migrate.js'),
        import('./src/db.js'),
        import('./src/services/push.js'),
        import('./src/services/cleanup.js'),
      ]);

    await runMigrations();
    await run('SELECT 1');
    await initFcm();
    startMaintenanceLoop();

    const app = await createApp();
    await new Promise((resolve, reject) => {
      const server = app.listen(config.port, () => {
        console.log(`[ayx-backend] listening on :${config.port} (env=${config.nodeEnv})`);
        resolve();
      });
      server.on('error', reject);
    });
    return true;
  } catch (err) {
    console.error('[ayx-backend] failed to start:', err.message);
    // Don't throw — the Baileys gateway must keep running.
    return false;
  }
}
