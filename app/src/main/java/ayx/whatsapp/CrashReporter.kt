package ayx.whatsapp

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporter (v147). An uncaught-exception handler saves a plain-text
 * report of any crash — full stack trace, where it crashed, the thread,
 * the app version, the device model and the Android version — into the
 * app's private storage. On the next launch MainActivity finds the saved
 * report and offers a popup whose "Send crash report" button opens the
 * user's mail app with everything already filled in (recipient
 * raju@imayx.in, subject + body), so sending is a single tap there.
 * Android cannot send mail silently in the background without the user's
 * own mail account, so the popup + prefilled email is the delivery path.
 *
 * The previous default handler is always chained after saving, so crash
 * behaviour (process death, system tombstone) is unchanged.
 */
object CrashReporter {
    private const val REPORT_FILE = "last_crash.txt"

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeReport(appCtx, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeReport(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().trim()
        val firstLine = trace.lineSequence().firstOrNull().orEmpty()
        val text = buildString {
            appendLine("AyXWhatsapp crash report")
            appendLine("App version: ${versionName(ctx)} (${versionCode(ctx)})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine("Crash: $firstLine")
            appendLine()
            appendLine(trace)
        }
        File(ctx.filesDir, REPORT_FILE).writeText(text)
    }

    /** The saved report from a previous run, or null if there is none. */
    fun pending(ctx: Context): String? = runCatching {
        val f = File(ctx.filesDir, REPORT_FILE)
        if (f.exists()) f.readText().ifBlank { null } else null
    }.getOrNull()

    /** Drops the saved report (called after it was sent or dismissed). */
    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, REPORT_FILE).delete() }
    }

    /** Mail subject for a saved report: app version + the crash's headline. */
    fun subject(report: String): String {
        val version = report.lineSequence()
            .firstOrNull { it.startsWith("App version:") }
            ?.removePrefix("App version:")?.trim().orEmpty()
        val crash = report.lineSequence()
            .firstOrNull { it.startsWith("Crash:") }
            ?.removePrefix("Crash:")?.trim().orEmpty()
        return "AyXWhatsapp crash — $version — $crash".take(140)
    }

    fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun versionCode(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(-1)
}
