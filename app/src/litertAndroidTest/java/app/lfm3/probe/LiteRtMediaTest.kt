package app.lfm3.probe

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.litert.*
import app.lfm3.probe.models.ModelCatalog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

class LiteRtMediaTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun widePhotoRetainsBothEdges() {
        val file = File.createTempFile("qa-litert", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(40_000) { index -> when (index % 400) { in 0..99 -> Color.RED; in 300..399 -> Color.BLUE; else -> Color.GREEN } }
            bitmap.setPixels(pixels, 0, 400, 0, 0, 400, 100)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            val rgb = LiteRtMedia.photo(file)
            fun channel(x: Int, c: Int) = rgb[(256 * 512 + x) * 3 + c].toInt() and 255
            assertTrue(channel(8, 0) > 240); assertTrue(channel(256, 1) > 240); assertTrue(channel(503, 2) > 240)
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun pcmWavAcceptsMetadataChunksAndRejectsRateAndDurationMismatch() {
        val file = File.createTempFile("qa-litert", ".wav", context.cacheDir)
        fun wav(rate: Int, samples: Int) {
            val b = ByteBuffer.allocate(56 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(s: String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
            tag("RIFF"); b.putInt(b.capacity() - 8); tag("WAVE"); tag("JUNK"); b.putInt(4); b.putInt(0)
            tag("fmt "); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
            tag("data"); b.putInt(samples * 2); repeat(samples) { b.putShort(16384) }
            file.writeBytes(b.array())
        }
        try {
            wav(16000, 1600)
            val pcm = LiteRtMedia.audio(file); assertEquals(1600, pcm.size); assertEquals(0.5f, pcm[123], 0f)
            wav(8000, 1600)
            try { LiteRtMedia.audio(file); fail("Wrong sample rate accepted") } catch (_: IllegalArgumentException) { }
            wav(16000, 480001)
            try { LiteRtMedia.audio(file); fail("Long recording accepted") } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }

    @Test fun pinnedTokenizerPreservesUnicodeAndExplicitBos() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runModelTest") == "true")
        val pack = ModelCatalog.get("lfm3-omni-litert")
        val bundle = File(context.noBackupFilesDir, "models/${pack.directory}/${pack.weights.name}")
        assumeTrue(bundle.isFile)
        val bytes = RandomAccessFile(bundle, "r").use { input -> input.seek(49152); ByteArray(2623427 - 49152).also { input.readFully(it) } }
        val tokenizer = InflaterInputStream(bytes.inputStream(8, bytes.size - 8)).reader().use { Lfm3Tokenizer.load(it) }
        assertArrayEquals(intArrayOf(Tokens.BOS), tokenizer.encode("<|startoftext|>"))
        assertArrayEquals(intArrayOf(Tokens.IM_END), tokenizer.encode("<|im_end|>"))
        val text = "English café, 日本語, नमस्ते 🌱"
        assertEquals(text, tokenizer.decode(tokenizer.encode(text).toList()))
    }
}
