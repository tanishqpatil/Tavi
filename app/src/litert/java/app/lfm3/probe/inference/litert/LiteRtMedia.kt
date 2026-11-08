package app.lfm3.probe.inference.litert

import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object LiteRtMedia {
    /** The reviewed conversion expects full-image RGB stretched to 512, without a center crop. */
    fun photo(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) { "Photo must be normalized before inference." }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "Private photo could not be decoded." }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgb = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                rgb[i * 3] = (pixels[i] shr 16).toByte(); rgb[i * 3 + 1] = (pixels[i] shr 8).toByte(); rgb[i * 3 + 2] = pixels[i].toByte()
            }
            return Bicubic.resizeBicubic(rgb, bitmap.width, bitmap.height, 512, 512)
        } finally { bitmap.recycle() }
    }

    /** Recorder contract: mono PCM16 WAV at 16 kHz, at most 30 seconds. No implicit truncation/resampling. */
    fun audio(file: File): FloatArray {
        require(file.length() in 44..1_000_000) { "Recorded audio exceeds its input budget." }
        val bytes = file.readBytes()
        fun label(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(label(0) == "RIFF" && label(8) == "WAVE") { "Recorded audio must be PCM WAV." }
        var pos = 12; var format = false; var data: IntRange? = null
        while (pos + 8 <= bytes.size) {
            val size = buffer.getInt(pos + 4).toLong() and 0xffffffffL
            require(size <= bytes.size - pos - 8) { "Truncated WAV chunk." }
            val start = pos + 8
            when (label(pos)) {
                "fmt " -> {
                    require(size >= 16 && buffer.getShort(start).toInt() == 1 && buffer.getShort(start + 2).toInt() == 1 &&
                        buffer.getInt(start + 4) == 16000 && buffer.getShort(start + 14).toInt() == 16) { "Audio must be mono PCM16 at 16 kHz." }
                    format = true
                }
                "data" -> { require(data == null); data = start until start + size.toInt() }
            }
            pos = (start.toLong() + size + (size and 1)).toInt()
        }
        require(format)
        val range = checkNotNull(data) { "WAV audio data is missing." }
        require(range.count() % 2 == 0 && range.count() / 2 in 1..480_000) { "Recorded audio must be at most 30 seconds." }
        return FloatArray(range.count() / 2) { buffer.getShort(range.first + it * 2) / 32768f }
    }
}
