package app.lfm3.probe.inference

import app.lfm3.probe.models.ModelCatalog
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun `every pack pins matching components from its own revision`() {
        for (pack in ModelCatalog.packs) {
            assertTrue(pack.revision.matches(Regex("[a-f0-9]{40}")))
            val origin = pack.weights.url.substringBeforeLast('/')
            assertTrue(pack.artifacts.all { it.url.substringBeforeLast('/') == origin && it.url.contains("/resolve/${pack.revision}/") })
            assertEquals("LICENSE", pack.license.name)
            if (pack.experimental) assertTrue(pack.projector!!.name.startsWith("mmproj-"))
        }
    }
    @Test fun `checkpoints cannot share their language weights accidentally`() {
        assertEquals(4, ModelCatalog.packs.map { it.weights.sha256 }.toSet().size)
        assertEquals(4, ModelCatalog.packs.map { it.directory }.toSet().size)
        assertEquals(1_099_638_382L, ModelCatalog.text.bytes)
    }
}
