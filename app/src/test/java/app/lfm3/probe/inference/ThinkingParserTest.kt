package app.lfm3.probe.inference

import app.lfm3.probe.presentation.parseThinking
import org.junit.Assert.*
import org.junit.Test

class ThinkingParserTest {
    @Test fun `closed and unclosed thoughts are separate from answers`() {
        val parts = parseThinking("<think>reason</think>answer<think>more")
        assertEquals(listOf(true, false, true), parts.map { it.thinking })
        assertEquals(listOf("reason", "answer", "more"), parts.map { it.text })
        assertFalse(parts.last().complete)
    }
    @Test fun `split streaming tags never leak into answer`() {
        assertEquals("answer", parseThinking("answer<thi").single().text)
        assertEquals("reason", parseThinking("<think>reason</thi").single().text)
        assertEquals("plain", parseThinking("plain").single().text)
    }
    @Test fun `output reserve is constrained by context`() {
        assertThrows(IllegalArgumentException::class.java) { GenerationLimits(1024, 1024) }
        assertEquals(2048, GenerationLimits(8192, 2048).output)
    }
    @Test fun `open tag immediately creates a thinking disclosure`() {
        assertTrue(parseThinking("<think>").single().thinking)
        assertFalse(parseThinking("<think>").single().complete)
        assertEquals("a <", parseThinking("a <", streaming = false).single().text)
    }
}
