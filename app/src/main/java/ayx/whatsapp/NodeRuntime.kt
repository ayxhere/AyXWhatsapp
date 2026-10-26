package ayx.whatsapp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Owns the single embedded node process: extracts the bundled node project on
 * first run (or after an app update), sets the env node needs on Android, and
 * starts it exactly once per OS process.
 *
 * v195: the once-per-process rule is load-bearing, not aspirational.
 * Embedded node::Start asserts (SIGABRT, whole app dies) if called twice in
 * one OS process — V8/platform globals from the first Start are still live.
 * The old code reset the start flag when node exited so the next
 * ensureStarted() restarted node in-process; that second Start is the
 * 2026-10-27 tombstone crash class (assert directly inside node::Start,
 * thread "nodejs", seconds after launch: node exits fast, then the 3 s
 * status-poll loop re-ensures). So the start gate below is NEVER reset once
 * nativeStart has been invoked. After a node exit the gateway stays down
 * until a FRESH process starts it (app relaunch, or a NodeRestartReceiver
 * alarm, which always runs in a fresh process). A dead gateway is
 * recoverable; a SIGABRT is not.
 */
object NodeRuntime {
    private const val TAG = "NodeRuntime"
    private val started = AtomicBoolean(false)
    // v195: permanent per-process gate for nativeStart. Set by CAS on the
    // first ensureStarted(); reset ONLY if setup throws before nativeStart
    // ever runs (V8 never initialized, so a retry is safe). Never reset
    // after nativeStart has been invoked.
    private val startAttempted = AtomicBoolean(false)

    const val PORT = 8765

    /** True while the embedded Node thread is believed alive. */
    fun isStarted(): Boolean = started.get()

    fun ensureStarted(ctx: Context) {
        // Integrity gate: the gateway engine never starts on a failed
        // verdict (re-signed/modded build), no matter which entry point
        // asks — service, receiver or UI retry.
        if (!AyxIntegrity.ok(ctx)) return
        // v195: the old `started` CAS was reset when node exited, so the
        // next ensureStarted() (e.g. the 3 s status-poll loop, a send path,
        // or a service redelivery) invoked nativeStart a SECOND time in
        // this process — and embedded node::Start asserts on a second call
        // (2026-10-27 tombstones: assert directly inside node::Start,
        // thread "nodejs", seconds after launch). startAttempted is the
        // permanent gate: once nativeStart has been invoked, every later
        // ensureStarted() in this process is a no-op.
        if (!startAttempted.compareAndSet(false, true)) return

        // Setup may throw (extraction, migration, auth init). If it throws
        // BEFORE nativeStart runs, V8 was never initialized, so reset the
        // gate and let a later ensureStarted() retry. projectDir/mainJs are
        // pure path constructions and stay outside the try.
        val projectDir = File(ctx.filesDir, "nodejs-project")
        try {
            // v148 session privacy: BEFORE node reads the auth dir, run the
            // one-time migration/purge of the legacy public session backup
            // (Download/WhatsAyX/session). A stranded backup is imported into
            // the private auth dir only when no private session exists, and
            // the public copy is then deleted for good. Synchronous on
            // purpose — extractIfNeeded() below already does blocking IO here.
            SessionMigration.runBlockingOnce(ctx)

            // SECURITY: Generate per-installation gateway auth token BEFORE starting Node,
            // so it can be passed via environment (process-private, not on command line).
            val apiToken = GatewayAuth.init(ctx)

            // Smart AI reminders: static app context for the reminder store/scheduler.
            // (Chat composer and .vv are frozen — this init lives at app startup.)
            Reschedule.init(ctx.applicationContext)

            // v168: cache the app context for AiKeyStore (app-side encrypted copy
            // of the real aiApiKey) so context-less callers (GatewayClient) can
            // read it. Runs before the gateway serves any settings.
            AiKeyStore.init(ctx)

            // v170: same early init for the settings mirror (Drive backup's
            // encrypted source of real key/prompt values, fed by the
            // GatewayClient.patchSettings choke point) and DriveBackup itself.
            SettingsMirror.init(ctx)
            DriveBackup.init(ctx)

            extractIfNeeded(ctx, projectDir)

            // Android has no /tmp and no HOME — node/npm code assumes both exist.
            val authDir = File(ctx.noBackupFilesDir, "auth").apply { mkdirs() }
            val compileCache = File(ctx.cacheDir, "node-compile-cache").apply { mkdirs() }

            NodeBridge.nativeSetenv("TMPDIR", ctx.cacheDir.absolutePath)
            NodeBridge.nativeSetenv("HOME", ctx.filesDir.absolutePath)
            NodeBridge.nativeSetenv("NODE_COMPILE_CACHE", compileCache.absolutePath)
            // V8 sizes old-space from total RAM; cap it so the OS doesn't OOM-kill the app.
            NodeBridge.nativeSetenv("NODE_OPTIONS", "--max-old-space-size-percentage=50")
            NodeBridge.nativeSetenv("WAGW_AUTH_DIR", authDir.absolutePath)
            NodeBridge.nativeSetenv("WAGW_PORT", PORT.toString())
            // SECURITY: Gateway API token — the Node.js gateway requires this on
            // sensitive routes. Passed via env (not argv) to avoid /proc/cmdline exposure.
            // Never log this value.
            NodeBridge.nativeSetenv("WAGW_API_TOKEN", apiToken)
            val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
            NodeBridge.nativeSetenv("WAGW_MEDIA_DIR", mediaDir.absolutePath)
        } catch (e: Exception) {
            startAttempted.set(false)
            Log.w(TAG, "node setup failed before start, will retry on next ensureStarted(): ${e.message}")
            return
        }

        val mainJs = File(projectDir, "main.js").absolutePath
        started.set(true)
        Thread({
            Log.i(TAG, "starting node: $mainJs")
            val rc = NodeBridge.nativeStart(arrayOf("node", mainJs), projectDir.absolutePath)
            // v195: do NOT reset startAttempted here. A second nativeStart
            // in this process asserts inside node::Start and SIGABRTs the
            // app (2026-10-27 tombstones). `started` is liveness only, so
            // it still drops — but the gateway only comes back in a FRESH
            // process (app relaunch / NodeRestartReceiver alarm).
            started.set(false)
            Log.w(TAG, "node exited rc=$rc — gateway stays down until a fresh process starts it again (app relaunch / restart alarm)")
        }, "nodejs").start()
    }

    private fun extractIfNeeded(ctx: Context, projectDir: File) {
        val marker = File(projectDir, ".version")
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == current) return

        Log.i(TAG, "extracting nodejs-project.zip (v=$current)")
        projectDir.deleteRecursively()
        projectDir.mkdirs()

        ctx.assets.open("nodejs-project.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                var e = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    val out = File(projectDir, e.name)
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            var n = zip.read(buf)
                            while (n >= 0) { fos.write(buf, 0, n); n = zip.read(buf) }
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        marker.writeText(current)
        Log.i(TAG, "extraction done")
    }
}
