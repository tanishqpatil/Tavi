package app.lfm3.probe.inference

import app.lfm3.probe.presentation.ProbeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {
    @Test fun `cancelled engine cannot write a stale response or usage`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val engine = ControlledEngine()
            val model = ProbeViewModel(engine)
            model.updateDraft("test")
            model.submit()
            runCurrent()
            model.cancel()
            engine.emit(InferenceEvent.TextDelta(1, "late"))
            engine.emit(InferenceEvent.Usage(1, 100, 1_000_000_000))
            assertFalse(model.state.value.isGenerating)
            assertNull(model.state.value.metrics.tokensPerSecond)
            assertFalse(model.state.value.turns.toString().contains("late"))
            runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `runtime usage becomes a measured decode rate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val engine = ControlledEngine()
            val model = ProbeViewModel(engine)
            model.updateDraft("test")
            model.submit()
            runCurrent()
            engine.emit(InferenceEvent.Usage(1, 50, 2_000_000_000))
            assertEquals(25.0, model.state.value.metrics.tokensPerSecond!!, 0.001)
            model.cancel()
            runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    private class ControlledEngine : InferenceEngine {
        override val descriptor = EngineDescriptor("test", setOf(Capability.TEXT), EngineKind.DETERMINISTIC_PROBE)
        lateinit var emit: suspend (InferenceEvent) -> Unit
        override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) {
            this.emit = emit
            awaitCancellation()
        }
    }
}
