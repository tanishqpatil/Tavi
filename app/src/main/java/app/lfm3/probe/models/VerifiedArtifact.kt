package app.lfm3.probe.models

import java.io.File
import java.security.MessageDigest

data class VerifiedArtifact(val name: String, val bytes: Long, val sha256: String, val url: String) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
        require(bytes > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
        require(url.startsWith("https://huggingface.co/") && Regex("/resolve/[a-f0-9]{40}/").containsMatchIn(url))
    }
    fun verify(file: File) {
        check(file.length() == bytes) { "Artifact byte count mismatch." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == sha256) { "Artifact checksum mismatch." }
    }
}

object TextPack {
    const val revision = "cd0c74c575b5c416f3011b5b4c298077f26ca8c2"
    private const val base = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/$revision/"
    val weights = VerifiedArtifact("LFM3-1.7B-Q4_K_M.gguf", 1099627808, "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4", base + "LFM3-1.7B-Q4_K_M.gguf")
    val license = VerifiedArtifact("LICENSE", 10574, "4d28ca14dedc0b3d0fcc2b3339f0e79931faa33874f3d24f522183a8fc70068c", base + "LICENSE")
    val artifacts = listOf(weights, license)
    const val licensePage = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/blob/$revision/LICENSE"
}
