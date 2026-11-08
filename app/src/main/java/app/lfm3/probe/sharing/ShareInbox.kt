package app.lfm3.probe.sharing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class IncomingShare(val id: String = UUID.randomUUID().toString(), val text: String = "", val image: Uri? = null, val error: String? = null)

/** Shares are untrusted input, never executable requests. Keep URI grants only until explicit import. */
object ShareInput {
    fun parse(intent: Intent): IncomingShare? {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        return try {
            require(intent.action == Intent.ACTION_SEND) { "Share one photo or text selection at a time." }
            val type = intent.type?.lowercase().orEmpty()
            val clip = intent.clipData
            require(clip == null || clip.itemCount <= 1) { "Share one photo or text selection at a time." }
            val sequence = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
            require(sequence == null || sequence.length <= 8000) { "Shared text exceeds 8000 characters. Share a shorter selection." }
            val text = sequence?.toString().orEmpty()
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            when {
                type == "text/plain" -> {
                    require(stream == null && text.isNotBlank()) { "Share plain text without file attachments, or share one photo." }
                    IncomingShare(text = text)
                }
                type in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/bmp") -> {
                    val clipUri = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                    require(stream == null || clipUri == null || stream == clipUri) { "The share contains ambiguous photo attachments." }
                    val uri = stream ?: clipUri
                    require(uri?.scheme == "content" && !uri.authority.isNullOrBlank() && uri.toString().length <= 2048) {
                        "Share a photo through Android's sharing menu. File paths and remote image URLs are not imported."
                    }
                    IncomingShare(text = text, image = uri)
                }
                else -> error("Supported shares: plain text or one still JPEG, PNG, WebP, BMP or HEIF photo.")
            }
        } catch (error: IllegalArgumentException) { IncomingShare(error = error.message) }
        catch (error: IllegalStateException) { IncomingShare(error = error.message) }
        catch (_: Exception) { IncomingShare(error = "Unable to read this share. Share plain text or one photo again.") }
    }
}

class ShareInbox : ViewModel() {
    private val _pending = MutableStateFlow<IncomingShare?>(null)
    val pending = _pending.asStateFlow()
    var handled = true
        private set
    fun receive(intent: Intent) {
        ShareInput.parse(intent)?.let { _pending.value = it; handled = false }
    }
    fun dismiss(id: String) {
        if (_pending.value?.id == id) { _pending.value = null; handled = true }
    }
}
