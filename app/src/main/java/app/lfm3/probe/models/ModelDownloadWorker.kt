package app.lfm3.probe.models

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.app.NotificationManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.lfm3.probe.TaviApplication
import kotlinx.coroutines.*

/** Android 8–13 fallback: WorkManager persists constraints/retries and owns its foreground service. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val downloads get() = (applicationContext as TaviApplication).downloads
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = DownloadNotifications.notification(applicationContext, downloads.state.value)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(DownloadNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(DownloadNotifications.ID, notification)
    }
    @android.annotation.SuppressLint("MissingPermission") // Denial hides drawer notifications; foreground task controls remain system-managed.
    override suspend fun doWork(): Result = coroutineScope {
        val token = inputData.getString("token") ?: return@coroutineScope Result.failure()
        if (downloads.state.value.token != token || !downloads.state.value.active) return@coroutineScope Result.success()
        // CoroutineWorker.onStopped is final. This child promptly disconnects a blocked socket on cancellation.
        val stopWatcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { downloads.interrupt(token) } }
        try {
            setForeground(getForegroundInfo())
            when (downloads.run(token) { progress ->
                applicationContext.getSystemService(NotificationManager::class.java).notify(DownloadNotifications.ID,
                    DownloadNotifications.notification(applicationContext, progress))
            }) {
                TransferResult.COMPLETE, TransferResult.STOPPED -> Result.success()
                TransferResult.RETRY -> Result.retry()
                TransferResult.FAILED -> Result.failure()
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { downloads.fail(token); Result.failure() }
        finally { withContext(NonCancellable) { stopWatcher.cancelAndJoin() } }
    }
}
