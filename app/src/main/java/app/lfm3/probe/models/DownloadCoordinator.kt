package app.lfm3.probe.models

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.*
import app.lfm3.probe.diagnostics.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal enum class TransferResult { COMPLETE, RETRY, FAILED, STOPPED }

/** Application-owned transfer state; neither Activity nor native process owns a download. */
class DownloadCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        runCatching { running?.second?.interrupt() }
        _state.value = _state.value.copy(status = DownloadStatus.FAILED, message = "Unable to save or schedule the download. Check storage and retry.")
    })
    private val journal = DownloadJournal(File(context.noBackupFilesDir, "downloads/current.json"))
    private val _state = MutableStateFlow(journal.read().let { record -> when (record.status) {
        DownloadStatus.RUNNING -> record.copy(status = DownloadStatus.QUEUED, message = "Recovering transfer; waiting for Android…")
        DownloadStatus.PAUSING -> record.copy(status = DownloadStatus.PAUSED, message = "Download paused. Partial files retained.")
        else -> record
    } })
    val state = _state.asStateFlow()
    private val mutation = Any()
    private val operation = Mutex()
    @Volatile private var running: Pair<String, PackInstaller>? = null
    @Volatile private var scheduling = false
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private val root get() = File(context.noBackupFilesDir, "models")

    private fun update(token: String? = null, change: (DownloadRecord) -> DownloadRecord): DownloadRecord = synchronized(mutation) {
        val old = _state.value
        if (token != null && token != old.token) return@synchronized old
        val next = change(old)
        journal.write(next); _state.value = next
        next
    }

    suspend fun enqueue(pack: ModelPack, unmetered: Boolean) = withContext(Dispatchers.IO) {
        synchronized(mutation) {
            check(!_state.value.active && running == null && !scheduling) { "Previous transfer is still stopping. Retry shortly." }
            scheduling = true
        }
        val token = UUID.randomUUID().toString()
        try {
            val installer = PackInstaller(root, pack = pack)
            update { DownloadRecord(pack.id, pack.fingerprint, token, DownloadStatus.QUEUED, installer.partialBytes(), unmetered,
                message = if (unmetered) "Waiting for an unmetered network and Android scheduling…" else "Queued; mobile data is allowed.") }
            if (Build.VERSION.SDK_INT >= 34) {
                val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).apply {
                    if (unmetered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }.build()
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, ModelDownloadJobService::class.java))
                    .setExtras(PersistableBundle().apply { putString("token", token) })
                    .setRequiredNetwork(request).setUserInitiated(true).setPersisted(true)
                    .setEstimatedNetworkBytes((pack.bytes - installer.partialBytes()).coerceAtLeast(1), 0)
                    .build()
                val result = withContext(Dispatchers.Main.immediate) { scheduler.schedule(info) }
                check(result == JobScheduler.RESULT_SUCCESS) { "Android could not start this transfer. Open Tavi and tap Download again." }
            } else {
                val work = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setInputData(workDataOf("token" to token))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work).result.get(10, TimeUnit.SECONDS)
            }
        } catch (error: Exception) {
            update(token) { it.copy(status = DownloadStatus.PAUSED, message = "Unable to schedule. Keep Tavi visible and tap Download to resume.") }
            throw IllegalStateException("Android could not schedule the download. Open Tavi and retry.", error)
        } finally { scheduling = false }
    }

    fun pause(token: String = _state.value.token): Job = scope.launch {
        val record = _state.value
        if (token != record.token || !record.active) return@launch
        update(token) { it.copy(status = DownloadStatus.PAUSING, message = "Pausing and saving partial files…") }
        interrupt(token)
        scheduler.cancel(JOB_ID)
        if (Build.VERSION.SDK_INT < 34) WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).result.get(10, TimeUnit.SECONDS)
        if (running?.first != token) update(token) { it.copy(status = DownloadStatus.PAUSED, message = "Download paused. Tap Download to resume.") }
    }
    internal fun interrupt(token: String) {
        val installer = running?.takeIf { it.first == token }?.second ?: return
        // JobService stop callbacks have a short main-thread deadline; closing a socket can block.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) scope.launch { runCatching { installer.interrupt() } }
        else runCatching { installer.interrupt() }
    }
    internal suspend fun fail(token: String) = withContext(Dispatchers.IO) {
        val failure = _state.value.copy(status = DownloadStatus.FAILED, message = "Unable to complete this transfer. Check storage/connectivity and tap Download to resume.")
        synchronized(mutation) {
            if (_state.value.token == token) { runCatching { journal.write(failure) }; _state.value = failure }
        }
    }
    suspend fun forget(packId: String) = withContext(Dispatchers.IO) {
        if (_state.value.packId == packId) { check(!_state.value.active && running == null); update { DownloadRecord() } }
    }

    /** Never restart a force-stopped/task-manager-stopped transfer without another user action. */
    fun reconcile(): Job = scope.launch {
        val record = _state.value
        if (!record.active || running != null || scheduling) return@launch
        val present = if (Build.VERSION.SDK_INT >= 34) scheduler.getPendingJob(JOB_ID)?.extras?.getString("token") == record.token
        else WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
        if (!present) update(record.token) { it.copy(status = DownloadStatus.PAUSED, message = "Transfer stopped or was interrupted. Tap Download to resume its partial files.") }
    }

    internal suspend fun run(token: String, network: Network? = null, publish: (DownloadRecord) -> Unit): TransferResult = withContext(Dispatchers.IO) {
        val record = _state.value
        if (record.token != token || !record.active || record.status == DownloadStatus.PAUSING) return@withContext TransferResult.STOPPED
        val pack = ModelCatalog.packs.firstOrNull { it.id == record.packId && it.fingerprint == record.identity }
        if (pack == null) { update(token) { it.copy(status = DownloadStatus.FAILED, message = "Catalog identity changed. Select and download the current pack explicitly.") }; return@withContext TransferResult.FAILED }
        if (!operation.tryLock()) return@withContext TransferResult.RETRY
        val installer = PackInstaller(root, AppLog(context)::record, pack) { url ->
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val assigned = network ?: connectivity.activeNetwork ?: throw IOException("Waiting for a network.")
            if (record.unmetered && connectivity.getNetworkCapabilities(assigned)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
                throw IOException("Waiting for an unmetered network.")
            assigned.openConnection(url) as HttpsURLConnection
        }
        running = token to installer
        var lastUpdate = 0L
        try {
            currentCoroutineContext().ensureActive()
            val initial = update(token) { it.copy(status = DownloadStatus.RUNNING, attempts = it.attempts + 1,
                bytes = installer.partialBytes(), message = "Downloading and verifying ${pack.title}…") }
            publish(initial)
            if (!installer.verifyInstalled()) {
                check(!installer.installed.exists()) { "Installed files are damaged. Remove this pack before downloading again." }
                installer.install { bytes, total ->
                    if (_state.value.token != token || _state.value.status != DownloadStatus.RUNNING) throw CancellationException("Transfer stopped.")
                    if (System.nanoTime() - lastUpdate >= 1_000_000_000L || bytes == total) {
                        lastUpdate = System.nanoTime()
                        val progress = update(token) { if (it.status != DownloadStatus.RUNNING) it else it.copy(bytes = bytes,
                            message = if (bytes == total) "Verifying complete pack…" else "${bytes / 1_000_000} / ${total / 1_000_000} MB · downloading") }
                        publish(progress)
                    }
                }
            }
            update(token) { it.copy(status = DownloadStatus.COMPLETE, bytes = pack.bytes, message = "All pack components verified. Ready to load.") }
            TransferResult.COMPLETE
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                update(token) { it.copy(status = when (it.status) {
                    DownloadStatus.PAUSING -> DownloadStatus.PAUSED
                    DownloadStatus.FAILED, DownloadStatus.PAUSED, DownloadStatus.COMPLETE -> it.status
                    else -> DownloadStatus.QUEUED
                }, bytes = installer.partialBytes(), message = when (it.status) {
                    DownloadStatus.PAUSING -> "Download paused. Partial files retained."
                    DownloadStatus.FAILED, DownloadStatus.PAUSED, DownloadStatus.COMPLETE -> it.message
                    else -> "Interrupted by Android; waiting to resume…"
                }) }
            }
            throw error
        } catch (error: Exception) {
            val paused = _state.value.status == DownloadStatus.PAUSING
            val retry = !paused && error is IOException && (error !is DownloadHttpException || error.retryable) && _state.value.attempts < 5
            val safe = (error as? IllegalStateException)?.message?.takeIf { '/' !in it && it.length <= 180 }
            update(token) { it.copy(status = when { paused -> DownloadStatus.PAUSED; retry -> DownloadStatus.RETRY; else -> DownloadStatus.FAILED },
                bytes = installer.partialBytes(), message = when {
                    paused -> "Download paused. Partial files retained."
                    retry -> "Connection interrupted. Android will retry with backoff (${it.attempts}/5)."
                    else -> safe ?: "Download failed. Check connectivity/storage, then tap Download to resume."
                }) }
            when { paused -> TransferResult.STOPPED; retry -> TransferResult.RETRY; else -> TransferResult.FAILED }
        } finally {
            installer.interrupt(); running = null; operation.unlock()
            if (_state.value.token == token && _state.value.status == DownloadStatus.PAUSING) {
                withContext(NonCancellable) { update(token) { it.copy(status = DownloadStatus.PAUSED, message = "Download paused. Partial files retained.") } }
            }
        }
    }
    companion object { const val JOB_ID = 42034; private const val WORK_NAME = "tavi-model-download" }
}
