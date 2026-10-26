package ayx.whatsapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import java.security.MessageDigest

/**
 * Paytm-style app-integrity verdict, computed once per process and cached.
 *
 * Genuine = ALL of:
 *  1. The running APK is signed with the release key (SHA-256 of the signing
 *     cert DER matches the expected digest, stored XOR-masked below so the
 *     fingerprint never appears as a plain literal). A re-signed "mod" APK
 *     can never match — Android forces a re-sign on any modification.
 *  2. The AyX API endpoint blob (AyxEndpoints) still decodes to the expected
 *     base URL — an API-stripped or endpoint-swapped mod fails here.
 *
 * Debuggable builds (AndroidIDE test builds) skip everything and always
 * pass, so development can never lock itself out. In release builds any
 * exception fails CLOSED (verdict false). The verdict depends only on local
 * integrity — server reachability never affects it.
 */
object AyxIntegrity {
    // Expected signing-cert SHA-256 digest, XOR-masked (key 0x6C).
    private val CERT_MASKED = byteArrayOf(47, -51, 120, 124, -100, 90, -128, -53, 59, -93, -87, -61, -68, 62, 99, 4, -5, -23, 75, -6, 42, -37, 13, -92, 2, 86, -85, 34, -121, -21, 79, 88)
    private const val CERT_KEY = 0x6C

    // Second, independently masked copy of the expected API base URL
    // (key 0x37 — deliberately different from AyxEndpoints' own key).
    private val BASE_MASKED = byteArrayOf(95, 67, 67, 71, 68, 13, 24, 24, 86, 71, 94, 25, 94, 90, 86, 78, 79, 25, 94, 89)
    private const val BASE_KEY = 0x37

    @Volatile private var cached: Boolean? = null
    @Volatile private var cachedSigHex: String? = null
    @Volatile private var sigComputed = false

    fun ok(ctx: Context): Boolean {
        cached?.let { return it }
        val v = compute(ctx.applicationContext)
        cached = v
        return v
    }

    /**
     * v192 anti-mod guard (user spec 2026-10-27): call FIRST in
     * MainActivity.onCreate, before any UI. When the integrity verdict
     * fails (re-signed/modded APK or endpoint-swapped build), open
     * https://imayx.in in the browser and finish the activity — no
     * dialog, no delay, no block screen. Returns true when the app may
     * proceed. Debuggable builds always pass (same rule as ok()); any
     * failure computing the verdict fails CLOSED, like ok().
     */
    fun enforceAtStartup(activity: Activity): Boolean {
        if (ok(activity)) return true
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://imayx.in"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        activity.finish()
        return false
    }

    private fun compute(ctx: Context): Boolean {
        return try {
            if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true
            // API-config self-check: the endpoint blob must decode, and to
            // exactly the expected base URL.
            if (AyxEndpoints.base() != expectedBase()) return false
            val digest = ownCertSha256(ctx) ?: return false
            MessageDigest.isEqual(digest, expectedDigest())
        } catch (e: Exception) {
            false
        }
    }

    private fun expectedDigest(): ByteArray =
        ByteArray(CERT_MASKED.size) { i -> (CERT_MASKED[i].toInt() xor CERT_KEY).toByte() }

    private fun expectedBase(): String {
        val sb = StringBuilder(BASE_MASKED.size)
        for (b in BASE_MASKED) sb.append((b.toInt() xor BASE_KEY).toChar())
        return sb.toString()
    }

    /** SHA-256 (raw digest bytes) of this APK's own signing certificate, or null. */
    private fun ownCertSha256(ctx: Context): ByteArray? {
        return try {
            val pm = ctx.packageManager
            val certs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val sig = certs?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * This APK's own signing-cert SHA-256 in colon-separated uppercase hex
     * (keytool format) — sent to the backend as "app_signature" with the
     * installation register/heartbeat calls so the server can cross-check
     * it later. Null when the certificate cannot be read.
     */
    fun signatureSha256Hex(ctx: Context): String? {
        if (sigComputed) return cachedSigHex
        val digest = ownCertSha256(ctx.applicationContext)
        cachedSigHex = digest?.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
        sigComputed = true
        return cachedSigHex
    }
}
