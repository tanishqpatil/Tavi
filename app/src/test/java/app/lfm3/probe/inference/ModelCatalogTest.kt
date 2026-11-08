package app.lfm3.probe.inference

import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.ModelRuntime
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun `every pack pins matching components from its own revision`() {
        for (pack in ModelCatalog.packs) {
            assertTrue(pack.revision.matches(Regex("[a-f0-9]{40}")))
            val origin = pack.weights.url.substringBefore("/resolve/")
            assertTrue(pack.artifacts.all { it.url.startsWith("$origin/resolve/${pack.revision}/") })
            assertEquals("LICENSE", pack.license.name)
            if (pack.runtime == ModelRuntime.GGUF && pack.experimental) assertTrue(pack.projector!!.name.startsWith("mmproj-"))
            if (pack.runtime == ModelRuntime.LITERT) {
                assertNull(pack.projector)
                assertEquals(5, pack.artifacts.size)
                assertTrue(pack.workingBytes > pack.bytes)
                assertEquals(pack.artifacts.size, pack.artifacts.map { it.name }.toSet().size)
            }
        }
    }
    @Test fun `checkpoints cannot share their language weights accidentally`() {
        assertEquals(ModelCatalog.packs.size, ModelCatalog.packs.map { it.weights.sha256 }.toSet().size)
        assertEquals(ModelCatalog.packs.size, ModelCatalog.packs.map { it.directory }.toSet().size)
        assertEquals(1_099_638_382L, ModelCatalog.text.bytes)
    }
}
