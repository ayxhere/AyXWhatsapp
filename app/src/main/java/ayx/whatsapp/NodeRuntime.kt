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

    fun ensureStarted(ctx: Context) {
        if (!started.compareAndSet(false, true)) return

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
        val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
        NodeBridge.nativeSetenv("WAGW_MEDIA_DIR", mediaDir.absolutePath)

        // AyX Group backend (Express API on :3101) — runs in the same Node
        // process as the gateway. No Termux/proot needed.
        val backendDir = File(ctx.filesDir, "ayx-backend").apply { mkdirs() }
        val backendDb = File(backendDir, "data/ayx.db")
        NodeBridge.nativeSetenv("AYX_BACKEND_ENABLED", "1")
        NodeBridge.nativeSetenv("PORT", "3101")
        NodeBridge.nativeSetenv("NODE_ENV", "production")
        NodeBridge.nativeSetenv("DB_PATH", backendDb.absolutePath)
        NodeBridge.nativeSetenv("UPLOAD_DIR", File(backendDir, "uploads").apply { mkdirs() }.absolutePath)
        NodeBridge.nativeSetenv("SESSION_SECRET", getOrCreateBackendSecret(ctx))
        NodeBridge.nativeSetenv("LOCAL_OP_TOKEN", getOrCreateLocalOpToken(ctx))

        val mainJs = File(projectDir, "main.js").absolutePath
        Thread({
            Log.i(TAG, "starting node: $mainJs")
            val rc = NodeBridge.nativeStart(arrayOf("node", mainJs), projectDir.absolutePath)
            Log.w(TAG, "node exited rc=$rc") // node::Start only returns on process exit
        }, "nodejs").start()
    }

    /**
     * Backend SESSION_SECRET — generated once, stored in EncryptedSharedPreferences.
     * The backend requires >= 32 chars; we generate 48 bytes (96 hex chars).
     */
    private fun getOrCreateBackendSecret(ctx: Context): String {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            "ayx_backend",
            "ayx_backend_key",
            ctx,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        var secret = prefs.getString("session_secret", null)
        if (secret.isNullOrBlank() || secret.length < 32) {
            val bytes = ByteArray(48)
            java.security.SecureRandom().nextBytes(bytes)
            secret = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("session_secret", secret).apply()
            Log.i(TAG, "generated new AyX backend session secret")
        }
        return secret
    }

    /**
     * Token guarding the backend's one-time local admin bootstrap endpoint.
     * Generated once, stored alongside the session secret.
     */
    private fun getOrCreateLocalOpToken(ctx: Context): String {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            "ayx_backend",
            "ayx_backend_key",
            ctx,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        var token = prefs.getString("local_op_token", null)
        if (token.isNullOrBlank() || token.length < 32) {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            token = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("local_op_token", token).apply()
        }
        return token
    }

    private fun extractIfNeeded(ctx: Context, projectDir: File) {        val marker = File(projectDir, ".version")
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
