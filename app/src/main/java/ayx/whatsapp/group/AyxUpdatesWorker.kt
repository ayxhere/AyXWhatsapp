package ayx.whatsapp.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ayx.whatsapp.NotificationHelper
import java.util.concurrent.TimeUnit

/**
 * Periodic AyX Group sync (every 15 minutes, only when connected).
 *
 * This is the delivery path for "AyX updates" announcements — the app has no
 * Firebase, so new announcements are discovered by polling and surfaced as
 * local notifications. The sync itself is idempotent (upserts by id, cursor
 * only advances after content is persisted), so re-running can never duplicate
 * content or re-notify for an already-seen announcement.
 */
class AyxUpdatesWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = AyxGroupSync.syncNow(applicationContext)
            // Only announcements that arrived with THIS sync and were not known
            // before — never the bootstrap seed, never repeats.
            result.newAnnouncements.forEach { a ->
                val title = if (a.title.isNotBlank()) a.title else "AyX update"
                NotificationHelper.notifyUpdate(applicationContext, a.id, "✦ $title", a.body)
            }
            Result.success()
        } catch (e: Exception) {
            // Transient failure (network, 5xx): retry a few times, then give up
            // until the next periodic run. Revocation is handled inside syncNow.
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ayx_updates_sync"

        fun request() = PeriodicWorkRequestBuilder<AyxUpdatesWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        /** Enqueue once; KEEP means re-calling this is a no-op if already scheduled. */
        fun enqueue(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request(),
            )
        }
    }
}
