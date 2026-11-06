package app.lfm3.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lfm3.probe.inference.NativeLlama
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeReadinessTest {
    @Test fun nativeLibraryInitializesAndRejectsMissingModel() {
        assertTrue(NativeLlama.info().contains("8642541b2"))
        try { NativeLlama.load("/nonexistent/tavi.gguf"); fail("Missing model accepted") }
        catch (_: IllegalStateException) { /* Expected: a safe Java error, not a native crash. */ }
        NativeLlama.cancel()
        NativeLlama.unload()
    }
}
