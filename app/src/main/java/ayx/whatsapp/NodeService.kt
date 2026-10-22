package ayx.whatsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.os.IBinder
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Foreground service that keeps the node process (and the WhatsApp socket) alive. */
private fun notifIcon(c: android.content.Context): Int {
    val id = c.resources.getIdentifier("ic_notification", "drawable", c.packageName)
    return if (id != 0) id else android.R.drawable.ic_dialog_email
}

class NodeService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override fun onBind(intent: Intent?): IBinder? = null

    // F2: keep CPU + WiFi alive while the Node socket runs. Without these,
    // Doze/screen-off can pause Node's keepalive timers, leaving a zombie
    // socket that still reports "open". WAKE_LOCK permission is in the manifest.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AyXWhatsapp:NodeWakeLock")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock acquire failed: ${e.message}")
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AyXWhatsapp:NodeWifiLock")
                    .apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "wifiLock acquire failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // Integrity gate: on a failed verdict the gateway stays dead —
        // satisfy the foreground-service contract, then stop immediately
        // without starting Node or the polling loop.
        if (!AyxIntegrity.ok(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        NodeRuntime.ensureStarted(applicationContext)
        startNotifPolling()
        return START_STICKY
    }

    /**
     * App swiped away from recents. START_STICKY alone is NOT enough on
     * OEM skins (Xiaomi/Oppo/Vivo battery savers) — they can kill the
     * service without redelivery. Two restart paths, in order:
     * 1. Direct re-start (works when the process survives the swipe).
     * 2. AlarmManager restart via NodeRestartReceiver (works when the
     *    process is killed: the alarm fires in a fresh process and a
     *    receiver IS allowed to start a foreground service, unlike a
     *    direct startForegroundService() from the dying process which
     *    Android 12+ blocks). Force-Stop still blocks ALL restarts until
     *    the app is reopened (Android rule, cannot be bypassed).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved (swipe) — scheduling restart")
        restartIfAllowed("onTaskRemoved")
        if (allowRestart && runBackgroundOn()) NodeRestartReceiver.scheduleRestart(applicationContext)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()  // F2: always release, even on system kill
        // If the system killed us (not a user stop()), come back.
        restartIfAllowed("onDestroy")
        if (allowRestart && runBackgroundOn()) NodeRestartReceiver.scheduleRestart(applicationContext)
        super.onDestroy()
    }

    private fun runBackgroundOn(): Boolean {
        return try {
            ChatStyle.init(applicationContext)
            ChatStyle.runBackground.value
        } catch (_: Exception) {
            true
        }
    }

    private fun restartIfAllowed(reason: String) {
        if (!allowRestart || !runBackgroundOn()) return
        try {
            Log.i(TAG, "NodeService restarting after $reason")
            NodeRuntime.ensureStarted(applicationContext)
            val i = Intent(applicationContext, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    applicationContext.startForegroundService(i)
                } catch (_: Exception) {
                    // Android 12+ blocks background foreground-service
                    // starts; the next app open revives it instead.
                    applicationContext.startService(i)
                }
            } else {
                applicationContext.startService(i)
            }
        } catch (e: Exception) {
            Log.w(TAG, "restart after $reason failed: ${e.message}")
        }
    }

    // Guard: onStartCommand can run twice on one service instance
    // (START_STICKY redelivery) — without this, two polling loops run,
    // doubling gateway traffic and notification work.
    @Volatile private var pollingStarted = false

    private fun startNotifPolling() {
        if (pollingStarted) return
        pollingStarted = true
        scope.launch {
            val prefs = getSharedPreferences("wagw", Context.MODE_PRIVATE)
            var lastTs = prefs.getLong("notif_ts", System.currentTimeMillis())
            while (isActive) {
                try {
                    val msgs = GatewayClient.getMessages()
                    val maxTs = msgs.maxOfOrNull { it.ts } ?: lastTs
                    if (maxTs > lastTs) {
                        msgs.filter { !it.fromMe && !it.deleted && it.ts > lastTs && it.chat != currentOpenChat }
                            .groupBy { it.chat }.forEach { (chat, group) ->
                                val m = group.maxByOrNull { it.ts }!!
                                val dp = try { GatewayClient.dpBytes(chat)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (_: Exception) { null }
                                val body = if (m.text.isNotBlank()) m.text else if (m.mediaType != null) "[" + m.mediaType + "]" else ""
                                val name = group.firstOrNull { !it.name.isNullOrBlank() }?.name ?: chat.substringBefore("@")
                                NotificationHelper.notifyMessage(applicationContext, chat, name, body, dp)
                            }
                        lastTs = maxTs
                        prefs.edit().putLong("notif_ts", lastTs).apply()
                    }
                    // C1: flush queued outgoing messages in the background too —
                    // if the UI poll loop is delayed, queued sends still go out.
                    runCatching {
                        val st = GatewayClient.status()
                        if (st.reachable && st.connection == "open") GatewayClient.flushSendQueue(applicationContext)
                    }
                } catch (_: Exception) {}
                delay(4000)
            }
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN keeps the mandatory foreground-service notice out of the status bar / shade top
            val ch = NotificationChannel(CHANNEL, "Background service", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
            mgr.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("AyX")
            .setSmallIcon(notifIcon(this))
            .setOngoing(true)
            .setPriority(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) Notification.PRIORITY_MIN else 0)
            .build()
    }

    companion object {
        private const val TAG = "NodeService"

        /**
         * False only when the user explicitly turned "Run in background"
         * OFF (stop()). Guards onTaskRemoved/onDestroy restarts so the
         * OFF toggle is respected instead of the service resurrecting
         * itself forever.
         */
        @Volatile
        private var allowRestart = true

        @Volatile var currentOpenChat: String? = null
        // new channel id so the lowered importance actually applies on devices that had the old channel
        private const val CHANNEL = "wagw_engine_min"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            // Integrity gate: never (re)start the gateway on a failed verdict.
            if (!AyxIntegrity.ok(ctx)) return
            allowRestart = true
            val i = Intent(ctx, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            allowRestart = false
            runCatching { ctx.stopService(Intent(ctx, NodeService::class.java)) }
        }
    }
}
