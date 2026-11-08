package app.lfm3.probe.inference

import org.junit.Assert.*
import org.junit.Test

class CpuOptionsTest {
    @Test fun rejectsUnboundedExecutionRequestsBeforeNativeAllocation() {
        for (values in listOf(Triple(0, 4, 128), Triple(4, 64, 128), Triple(4, 4, 8192))) {
            try { CpuOptions(values.first, values.second, values.third); fail("Unbounded settings accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(CpuOptions(4, 4, 128), CpuOptions())
        assertEquals(64, CpuOptions(2, 4, 64).batch)
    }
}
