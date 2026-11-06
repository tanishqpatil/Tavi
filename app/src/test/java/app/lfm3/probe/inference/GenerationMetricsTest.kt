package app.lfm3.probe.inference

import app.lfm3.probe.presentation.GenerationMetrics
import org.junit.Assert.*
import org.junit.Test

class GenerationMetricsTest {
    @Test fun `missing tokenizer counts do not fabricate a speed`() {
        assertNull(GenerationMetrics(firstTextMs = 25).tokensPerSecond)
        assertNull(GenerationMetrics(outputTokens = 50, decodeNanos = 0).tokensPerSecond)
    }

    @Test fun `speed uses exact output tokens and decode duration`() {
        assertEquals(25.0, GenerationMetrics(50, 2_000_000_000).tokensPerSecond!!, 0.001)
    }
}
