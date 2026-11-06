package app.lfm3.probe.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Session attachments are normalized into private storage, never arbitrary paths or remote URLs. */
class MediaStore(private val context: Context) {
    val directory = File(context.noBackupFilesDir, "session-media").apply {
        mkdirs()
        // Application singleton initializes once per process; previous in-memory sessions no longer exist.
        listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
    fun resolve(reference: String): File {
        require(reference.matches(Regex("[a-f0-9-]+\\.(jpg|wav)"))) { "Invalid private attachment reference." }
        return File(directory, reference).also { check(it.isFile) { "A session attachment is missing. Start a new chat." } }
    }
    fun newFile(extension: String): File {
        check((directory.list()?.size ?: 0) < 64) { "Session attachment storage is full. Restart Tavi to clear old session media." }
        return File(directory, "${UUID.randomUUID()}.$extension")
    }
    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val raw = File.createTempFile("import", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                raw.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var bytes = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n; check(bytes <= 20 * 1024 * 1024) { "Photo exceeds 20 MB. Choose a smaller image." }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Photo access was denied.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "Unsupported photo dimensions." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val decoded = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Photo could not be decoded.")
            try {
                val orientation = runCatching { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                        7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                    }
                    val scale = minOf(1f, 1024f / maxOf(decoded.width, decoded.height)); postScale(scale, scale)
                }
                val normalized = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                try {
                    val file = newFile("jpg")
                    file.outputStream().use { check(normalized.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                    file.name
                } finally { if (normalized !== decoded) normalized.recycle() }
            } finally { decoded.recycle() }
        } finally {
            raw.delete()
            if (uri.authority == "${context.packageName}.files" && uri.path?.startsWith("/camera_capture/") == true) {
                uri.lastPathSegment?.takeIf { it.matches(Regex("[a-f0-9-]+\\.jpg")) }?.let { File(context.cacheDir, "captures/$it").delete() }
            }
        }
    }
}
