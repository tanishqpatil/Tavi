package app.lfm3.probe.models

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lfm3.probe.TaviApplication

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        val pending = goAsync()
        (context.applicationContext as TaviApplication).downloads.pause(token).invokeOnCompletion { pending.finish() }
    }
}
