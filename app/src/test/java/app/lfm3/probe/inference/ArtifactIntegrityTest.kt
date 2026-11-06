package app.lfm3.probe.inference

import app.lfm3.probe.models.VerifiedArtifact
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArtifactIntegrityTest {
    private val descriptor = VerifiedArtifact("model.gguf", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "https://huggingface.co/test/model/resolve/" + "a".repeat(40) + "/model.gguf")
    @Test fun `valid bytes pass but same-size corrupt bytes are rejected`() {
        val file = File.createTempFile("tavi-artifact", ".test")
        try {
            file.writeText("abc"); descriptor.verify(file)
            file.writeText("xyz")
            assertThrows(IllegalStateException::class.java) { descriptor.verify(file) }
            file.writeText("ab")
            assertThrows(IllegalStateException::class.java) { descriptor.verify(file) }
        } finally { file.delete() }
    }
    @Test fun `mutable download identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { descriptor.copy(url = "https://huggingface.co/test/model/resolve/main/model.gguf") }
        assertThrows(IllegalArgumentException::class.java) { descriptor.copy(name = "../model.gguf") }
    }
}
