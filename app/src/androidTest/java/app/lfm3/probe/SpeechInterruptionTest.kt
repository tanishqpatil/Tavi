package app.lfm3.probe

import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.media.AudioPlayback
import app.lfm3.probe.media.SpeechAudioFocus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SpeechInterruptionTest {
    @Test fun focusLossStopsOnceAndClosedLeaseCannotInterruptTheNextResponse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(700) // The OS requires the target UID to be foreground before granting audio focus.
        val manager = context.getSystemService(AudioManager::class.java)
        val firstStops = AtomicInteger()
        val nextStops = AtomicInteger()
        val focus = SpeechAudioFocus(context)
        val first = focus.acquire { firstStops.incrementAndGet() }
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(SpeechAudioFocus.attributes).setOnAudioFocusChangeListener {}.build()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
            withTimeout(3000) { while (firstStops.get() != 1) delay(20) }
            manager.abandonAudioFocusRequest(other)
            delay(100)
            assertEquals(1, firstStops.get()) // Focus gain must not restart/re-arm a stopped response.
            first.close(); first.close()
            val next = focus.acquire { nextStops.incrementAndGet() }
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
                withTimeout(3000) { while (nextStops.get() != 1) delay(20) }
                assertEquals(1, firstStops.get())
            } finally { next.close() }
        } finally { first.close(); manager.abandonAudioFocusRequest(other) }
    }

    @Test fun stoppedPcmCannotRestartWithoutANewPreparedResponse() = runBlocking {
        val playback = AudioPlayback()
        playback.prepare(); playback.interrupt()
        try { playback.write(FloatArray(16), 16000); fail("Stopped audio restarted") }
        catch (_: CancellationException) { }
        playback.release(); playback.release()
        playback.prepare(); playback.release()
    }
}
