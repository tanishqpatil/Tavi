package app.lfm3.probe.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** Blocking writes on the inference worker provide backpressure; there is no unbounded PCM queue. */
class AudioPlayback {
    @Volatile private var track: AudioTrack? = null
    private var rate = 0
    private var frames = 0L
    suspend fun write(pcm: FloatArray, sampleRate: Int) {
        if (pcm.isEmpty()) return
        require(sampleRate in 8000..48000 && pcm.all { it.isFinite() })
        if (track == null) {
            rate = sampleRate
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minimum > 0) { "Audio playback is unavailable on this device." }
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(minimum * 2).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        }
        check(rate == sampleRate) { "Speech sample rate changed unexpectedly." }
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            val n = track?.write(pcm, offset, minOf(4096, pcm.size - offset), AudioTrack.WRITE_BLOCKING) ?: 0
            check(n > 0) { "Audio output was interrupted." }
            offset += n
            frames += n
        }
    }
    suspend fun finish() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while ((track?.playbackHeadPosition?.toLong() ?: frames) < frames && System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive(); delay(10)
        }
    }
    fun interrupt() { runCatching { track?.pause(); track?.flush() } }
    fun release() { interrupt(); track?.release(); track = null; frames = 0 }
}
