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
 */
object NodeRuntime {
    private const val TAG = "NodeRuntime"
    private val started = AtomicBoolean(false)

    const val PORT = 8765

    /** True while the embedded Node thread is believed alive. */
    fun isStarted(): Boolean = started.get()

    fun ensureStarted(ctx: Context) {
        // Integrity gate: the gateway engine never starts on a failed
        // verdict (re-signed/modded build), no matter which entry point
        // asks — service, receiver or UI retry.
        if (!AyxIntegrity.ok(ctx)) return
        if (!started.compareAndSet(false, true)) return

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

        val projectDir = File(ctx.filesDir, "nodejs-project")
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

        val mainJs = File(projectDir, "main.js").absolutePath
        Thread({
            Log.i(TAG, "starting node: $mainJs")
            val rc = NodeBridge.nativeStart(arrayOf("node", mainJs), projectDir.absolutePath)
            // BUG FIX: previously `started` stayed true forever after Node
            // exited, so ensureStarted() became a permanent no-op and the
            // gateway never came back until the whole app process died.
            // Resetting here lets the next ensureStarted() restart Node.
            started.set(false)
            Log.w(TAG, "node exited rc=$rc — will restart on next ensureStarted()")
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
