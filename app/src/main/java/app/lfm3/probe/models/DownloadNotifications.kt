package app.lfm3.probe.models

import android.app.*
import android.content.Context
import android.content.Intent
import app.lfm3.probe.MainActivity

internal object DownloadNotifications {
    const val ID = 42034
    private const val CHANNEL = "model-downloads"
    fun notification(context: Context, record: DownloadRecord): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val pack = ModelCatalog.get(record.packId)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(context, 0, Intent(context, DownloadActionReceiver::class.java).putExtra("token", record.token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(pack.title).setContentText(record.message)
            .setContentIntent(open).setOngoing(record.active).setOnlyAlertOnce(true)
            .setProgress(100, (record.bytes * 100 / pack.bytes).toInt().coerceIn(0, 100), record.status != DownloadStatus.RUNNING)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }
}
