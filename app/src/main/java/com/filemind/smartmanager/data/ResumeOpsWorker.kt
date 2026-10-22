package com.filemind.smartmanager.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.filemind.smartmanager.ads.AdsManager
import com.filemind.smartmanager.core.ConflictDecision
import com.filemind.smartmanager.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Process-wide flags so the UI and the worker never run bulk operations at the same time. */
object BackgroundOps {
    val running = MutableStateFlow(false)
    @Volatile var uiRunning = false
}

/**
 * Resumes unfinished journaled operations in the background (foreground-service notification with a Stop action).
 * Non-interactive by design: on a name conflict it KEEPS the existing file (never replaces), logs it as skipped, and moves on.
 * Stopping leaves the remaining items pending so they can be resumed later.
 */
class ResumeOpsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_IDS = "op_ids"
        const val UNIQUE = "resume_ops"
        private const val CHANNEL = "filemind_ops"
        private const val NOTIF_ID = 4101

        fun enqueue(ctx: Context, opId: Long) {
            val req = OneTimeWorkRequestBuilder<ResumeOpsWorker>().setInputData(workDataOf(KEY_IDS to longArrayOf(opId))).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "عمليات الملفات", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FileMind")
            .setContentText("جارٍ استكمال عملية الملفات في الخلفية")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    override suspend fun doWork(): Result {
        val ids = inputData.getLongArray(KEY_IDS) ?: return Result.success()
        if (BackgroundOps.uiRunning) return Result.retry()
        BackgroundOps.running.value = true
        AdsManager.fileOperationInProgress = true
        try {
            runCatching { setForeground(getForegroundInfo()) }   // may be refused if started from the background; then it runs as normal work
            val db = AppDatabase.get(applicationContext)
            val backup = SettingsRepository(applicationContext).backupOnReplace.first()
            for (opId in ids) {
                if (isStopped) break
                PendingRunner.run(applicationContext, db, opId, backup, { _ -> ConflictDecision.KEEP_EXISTING }, { isStopped }) { _, _ -> }
                if (db.pending().remaining(opId).isEmpty()) { db.pending().deleteItems(opId); db.pending().deleteOp(opId) }
            }
            return Result.success()
        } finally {
            AdsManager.fileOperationInProgress = false
            BackgroundOps.running.value = false
        }
    }
}
