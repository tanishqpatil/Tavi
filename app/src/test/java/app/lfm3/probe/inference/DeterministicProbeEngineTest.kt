package app.lfm3.probe.inference

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicProbeEngineTest {
    @Test
    fun `emits a completed event for an image and audio turn`() = runTest {
        val events = mutableListOf<InferenceEvent>()
        DeterministicProbeEngine().generate(
            InferenceRequest(7, listOf(RequestPart.Text("What is shown?"), RequestPart.Image("fixture"), RequestPart.Audio("audio", 500))),
        ) { events += it }

        assertTrue(events.filterIsInstance<InferenceEvent.TextDelta>().isNotEmpty())
        assertEquals(InferenceEvent.Completed(7), events.last())
    }
}
