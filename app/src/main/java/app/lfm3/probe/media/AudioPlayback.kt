package app.lfm3.probe.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.Closeable

/** Blocking writes on the inference worker provide backpressure; there is no unbounded PCM queue. */
class AudioPlayback(context: Context? = null, private val onInterrupted: () -> Unit = {}) {
    private val focus = context?.let { SpeechAudioFocus(it) }
    private val ownership = Any()
    private var lease: Closeable? = null
    @Volatile private var interrupted = true
    @Volatile private var track: AudioTrack? = null
    private var rate = 0
    private var frames = 0L
    /** Called under the engine's generation mutex, before decoding a new response. */
    fun prepare() = synchronized(ownership) {
        check(track == null && lease == null) { "Previous speech playback has not finished stopping." }
        interrupted = false
    }
    private fun checkPlaying() { if (interrupted) throw CancellationException("Speech was interrupted; it will not resume automatically.") }
    suspend fun write(pcm: FloatArray, sampleRate: Int) {
        if (pcm.isEmpty()) return
        checkPlaying()
        require(sampleRate in 8000..48000 && pcm.all { it.isFinite() })
        if (track == null) {
            lease = checkNotNull(focus) { "Android audio context is unavailable for speech." }.acquire {
                interrupt(); onInterrupted()
            }
            rate = sampleRate
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minimum > 0) { "Audio playback is unavailable on this device." }
            val output = AudioTrack.Builder().setAudioAttributes(SpeechAudioFocus.attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(minimum * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            synchronized(ownership) {
                track = output
                checkPlaying()
                output.play()
            }
        }
        check(rate == sampleRate) { "Speech sample rate changed unexpectedly." }
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            checkPlaying()
            val n = track?.write(pcm, offset, minOf(4096, pcm.size - offset), AudioTrack.WRITE_BLOCKING) ?: 0
            checkPlaying()
            check(n > 0) { "Audio output was interrupted." }
            offset += n
            frames += n
        }
    }
    suspend fun finish() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while ((track?.playbackHeadPosition?.toLong() ?: frames) < frames && System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive(); delay(10)
            checkPlaying()
        }
    }
    fun interrupt() = synchronized(ownership) {
        interrupted = true
        runCatching { track?.pause(); track?.flush() }
    }
    fun release() {
        synchronized(ownership) {
            interrupt()
            val previous = track; track = null; frames = 0
            runCatching { previous?.release() }
        }
        val previous = lease; lease = null
        runCatching { previous?.close() }
    }
}
