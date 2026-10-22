package ayx.whatsapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the Node gateway after the app is swiped away from recents,
 * after a reboot, or after an app update.
 *
 * WHY A RECEIVER + ALARM (not just NodeService.onTaskRemoved):
 * the Node engine runs INSIDE the app process (NodeBridge thread). When
 * the user swipes the app away, Android kills the process — a direct
 * startForegroundService() call from the dying process is blocked on
 * Android 12+ (ForegroundServiceStartNotAllowedException) and the
 * service never comes back. An AlarmManager alarm, however, fires in a
 * fresh process, and a BroadcastReceiver IS allowed to start a
 * foreground service. So the swipe path is: onTaskRemoved schedules a
 * 5s exact alarm (+ a 30s inexact backup) -> this receiver -> NodeService.
 *
 * HONEST LIMITS (not faked): Force-Stop blocks ALL restarts until the app
 * is reopened (Android rule, cannot be bypassed). Xiaomi/Oppo/Vivo
 * battery savers can suppress alarms — the user needs Battery >
 * Unrestricted for the gateway to survive a swipe. If "Run in
 * background" is OFF, no restart is scheduled at all (user's choice).
 */
class NodeRestartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            // Integrity gate: no boot/update/alarm autostart of the gateway
            // on a failed verdict.
            if (!AyxIntegrity.ok(ctx)) {
                Log.i(TAG, "restart skipped: integrity verdict failed")
                return
            }
            ChatStyle.init(ctx)
            if (!ChatStyle.runBackground.value) {
                Log.i(TAG, "restart skipped: Run in background OFF")
                return
            }
            Log.i(TAG, "restart triggered by ${intent?.action}")
            NodeService.start(ctx.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "restart failed: ${e.message}")
        }
    }

    companion object {
        const val TAG = "NodeRestart"
        const val ACTION_RESTART = "ayx.whatsapp.ACTION_RESTART_NODE"

        /** AlarmManager restart: exact 5s + inexact 30s backup. */
        fun scheduleRestart(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(ctx, NodeRestartReceiver::class.java).setAction(ACTION_RESTART)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                val pi = PendingIntent.getBroadcast(ctx, 1001, intent, flags)
                val triggerAt = System.currentTimeMillis() + 5_000
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                } catch (_: Exception) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                // Backup: a second, inexact alarm 30s out, in case the
                // exact one is dropped by an OEM battery saver.
                val pi2 = PendingIntent.getBroadcast(ctx, 1002, intent, flags)
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30_000, pi2)
                } catch (_: Exception) { }
                Log.i(TAG, "restart alarms scheduled (5s + 30s)")
            } catch (e: Exception) {
                Log.w(TAG, "scheduleRestart failed: ${e.message}")
            }
        }
    }
}
