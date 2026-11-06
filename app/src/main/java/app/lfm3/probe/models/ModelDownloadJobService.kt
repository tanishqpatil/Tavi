package app.lfm3.probe.models

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import app.lfm3.probe.TaviApplication
import kotlinx.coroutines.*

/** Android 14+: persistent user-initiated transfer, exempt from ordinary job time quotas. */
class ModelDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads get() = (application as TaviApplication).downloads
    private var current: Pair<String, Job>? = null
    private var stopped: String? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val token = params.extras.getString("token") ?: return false
        val record = downloads.state.value
        if (record.token != token || !record.active || record.status == DownloadStatus.PAUSING) return false
        if (ModelCatalog.packs.none { it.id == record.packId && it.fingerprint == record.identity }) {
            scope.launch { downloads.fail(token) }; return false
        }
        setNotification(params, DownloadNotifications.ID, DownloadNotifications.notification(this, record), JOB_END_NOTIFICATION_POLICY_REMOVE)
        stopped = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val operationJob = coroutineContext[Job]
            var retry = false
            try {
                val network = params.network ?: error("Android did not provide a transfer network.")
                retry = downloads.run(token, network) { progress ->
                    android.os.Handler(mainLooper).post {
                        if (current?.second === operationJob && stopped != token && downloads.state.value.token == token) {
                            setNotification(params, DownloadNotifications.ID, DownloadNotifications.notification(this@ModelDownloadJobService, progress), JOB_END_NOTIFICATION_POLICY_REMOVE)
                        }
                    }
                } == TransferResult.RETRY
            } catch (_: CancellationException) { /* Android handles stopped-job rescheduling. */ }
            catch (_: Exception) { downloads.fail(token) }
            finally { withContext(NonCancellable + Dispatchers.Main) {
                if (current?.second === operationJob && stopped != token) { current = null; jobFinished(params, retry) }
            } }
        }
        current = token to job; job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        val token = params.extras.getString("token") ?: return false
        stopped = token
        downloads.interrupt(token)
        current?.takeIf { it.first == token }?.second?.cancel()
        val record = downloads.state.value
        return record.token == token && record.active && record.status != DownloadStatus.PAUSING
    }
    override fun onDestroy() { current?.let { downloads.interrupt(it.first) }; scope.cancel(); super.onDestroy() }
}
