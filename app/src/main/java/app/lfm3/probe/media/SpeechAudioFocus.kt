package app.lfm3.probe.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Each speech response owns one lease. Loss stops it permanently; gaining focus never replays audio. */
internal class SpeechAudioFocus(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)

    fun acquire(onInterrupted: () -> Unit): Closeable {
        val active = AtomicBoolean(true)
        val callbacks = Any()
        fun stop() = synchronized(callbacks) {
            if (active.compareAndSet(true, false)) onInterrupted()
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stop()
            }, Handler(Looper.getMainLooper())).build()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
            }
        }
        var registered = false
        val closed = AtomicBoolean(false)
        val lease = Closeable {
            if (closed.compareAndSet(false, true)) {
                // Also wait for any callback already inside onInterrupted before a later response can start.
                synchronized(callbacks) { active.set(false) }
                if (registered) runCatching { context.unregisterReceiver(receiver) }
                manager.abandonAudioFocusRequest(request)
            }
        }
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Speech could not obtain audio focus. Keep Tavi visible and try again after other audio stops."
            }
            return lease
        } catch (error: Exception) { lease.close(); throw error }
    }

    companion object {
        val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}
