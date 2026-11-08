package app.lfm3.probe.inference

import app.lfm3.probe.inference.litert.NativeResources
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeResourcesTest {
    @Test fun `partial construction releases buffers before models and closes each resource once`() {
        val closed = mutableListOf<String>()
        val resources = NativeResources()
        resources.own(AutoCloseable { closed += "environment" })
        val graph = resources.own(NativeResources())
        graph.own(AutoCloseable { closed += "model" })
        graph.own(AutoCloseable { closed += "buffer"; error("simulate close failure") })
        graph.close(); resources.close(); resources.close()
        assertEquals(listOf("buffer", "model", "environment"), closed)
    }
}
