package app.lfm3.probe

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.lfm3.probe.models.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class BackgroundDownloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val downloads get() = (context.applicationContext as TaviApplication).downloads
    private suspend fun visible() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty() }) delay(50) }
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } } }
    private fun preparePartial(missing: Long): PackInstaller {
        val root = File(context.noBackupFilesDir, "models"); val pack = ModelCatalog.text
        val installer = PackInstaller(root)
        assertTrue("Install the verified text pack before this opt-in test.", installer.verifyInstalled())
        PackLease.acquire(root, pack, false).use {
            val stage = File(root, "${pack.directory}.staging")
            assertFalse(stage.exists()); assertTrue(installer.installed.renameTo(stage))
            RandomAccessFile(File(stage, pack.weights.name), "rw").use { file -> file.setLength(pack.weights.bytes - missing); file.fd.sync() }
        }
        return installer
    }

    @Test fun journalRoundTripAndCorruptMetadataFailClosed() {
        val file = File(context.cacheDir, "download-journal-test-${java.util.UUID.randomUUID()}.json")
        val journal = DownloadJournal(file)
        val record = DownloadRecord(ModelCatalog.text.id, ModelCatalog.text.fingerprint, "fixture-token", DownloadStatus.PAUSED, 42, false, 2, "paused")
        try {
            journal.write(record); assertEquals(record, DownloadJournal(file).read())
            file.writeText("broken"); assertEquals(DownloadStatus.IDLE, journal.read().status)
        } finally { file.delete() }
    }

    /** Manual opt-in phase: leaves Wi-Fi off and a persisted queued job for a shell reboot/force-stop test. */
    @Test fun stageQueuedTransferForRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stageRestartTest") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        preparePartial(64L * 1024 * 1024)
        shell("svc wifi disable")
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        withTimeout(15_000) { while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) delay(50) }
        visible(); downloads.enqueue(ModelCatalog.text, true)
        val job = context.getSystemService(JobScheduler::class.java).getPendingJob(DownloadCoordinator.JOB_ID)
        assertNotNull(job); assertTrue(job!!.isPersisted)
        assertEquals(DownloadStatus.QUEUED, downloads.state.value.status)
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun resumeExistingPartialFromVisibleUserAction() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("resumeTransferTest") == "true")
        visible(); downloads.enqueue(ModelCatalog.text, true)
        withTimeout(180_000) { while (downloads.state.value.status != DownloadStatus.COMPLETE) {
            check(downloads.state.value.status != DownloadStatus.FAILED) { downloads.state.value.message }; delay(100)
        } }
        assertTrue(PackInstaller(File(context.noBackupFilesDir, "models")).verifyInstalled())
    }

    @Test fun realTransferPausesResumesAndCompletesWithScreenOff() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDownloadTest") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val power = context.getSystemService(PowerManager::class.java)
        val wasInteractive = power.isInteractive
        if (!wasInteractive) shell("input keyevent 224")
        val installer = preparePartial(256L * 1024 * 1024)
        try {
            visible()
            downloads.enqueue(ModelCatalog.text, true)
            val token = downloads.state.value.token
            val initial = installer.partialBytes()
            withTimeout(60_000) { while (downloads.state.value.status != DownloadStatus.RUNNING || downloads.state.value.bytes <= initial + 64 * 1024) {
                check(downloads.state.value.status != DownloadStatus.FAILED) { downloads.state.value.message }; delay(50)
            } }
            assertTrue(context.getSystemService(JobScheduler::class.java).getPendingJob(DownloadCoordinator.JOB_ID)!!.isPersisted)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DownloadNotifications.notification(context, downloads.state.value).actions.single().actionIntent.send()
            withTimeout(30_000) { while (downloads.state.value.status != DownloadStatus.PAUSED) delay(50) }
            val paused = installer.partialBytes(); delay(500); assertEquals(paused, installer.partialBytes())
            assertFalse(installer.installed.exists())
            assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(DownloadCoordinator.JOB_ID))
            assertEquals(DownloadStatus.PAUSED, DownloadJournal(File(context.noBackupFilesDir, "downloads/current.json")).read().status)
            visible(); downloads.enqueue(ModelCatalog.text, true)
            val resumed = downloads.state.value.token
            assertNotEquals(token, resumed)
            downloads.pause(token).join(); assertEquals(resumed, downloads.state.value.token); assertTrue(downloads.state.value.active)
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            shell("input keyevent 223")
            withTimeout(240_000) { while (downloads.state.value.status != DownloadStatus.COMPLETE) {
                check(downloads.state.value.status != DownloadStatus.FAILED) { downloads.state.value.message }; delay(100)
            } }
            assertTrue(installer.verifyInstalled())
            assertEquals(ModelCatalog.text.bytes, downloads.state.value.bytes)
        } finally {
            if (downloads.state.value.active) downloads.pause().join()
            if (wasInteractive) { shell("input keyevent 224"); visible() }
            else shell("input keyevent 223")
        }
    }
}
