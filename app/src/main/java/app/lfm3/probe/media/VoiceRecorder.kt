package app.lfm3.probe.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16, capped at 30 seconds. Capture finishes before inference/playback starts. */
class VoiceRecorder(private val store: MediaStore) {
    @Volatile private var stopRequested = false
    fun stop() { stopRequested = true }
    fun prepare() { stopRequested = false }
    @SuppressLint("MissingPermission") // Caller requests RECORD_AUDIO immediately before starting.
    suspend fun record(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz microphone capture is unavailable." }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
        val file = store.newFile("wav")
        var completed = false
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
            var count = 0
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0); output.write(ByteArray(44)); recorder.startRecording()
                val buffer = ByteArray(4096)
                while (!stopRequested && count < 16000 * 2 * 30) {
                    currentCoroutineContext().ensureActive()
                    val n = recorder.read(buffer, 0, minOf(buffer.size, 16000 * 2 * 30 - count))
                    check(n > 0) { "Microphone capture was interrupted." }
                    output.write(buffer, 0, n); count += n
                }
                check(count >= 3200) { "Recording was too short." }
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(count + 36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
                output.seek(0); output.write(header.array()); output.fd.sync()
                completed = true
                file.name to count * 1000L / 32000
            }
        } finally {
            runCatching { recorder.stop() }; recorder.release()
            if (!completed) file.delete()
        }
    }
}
