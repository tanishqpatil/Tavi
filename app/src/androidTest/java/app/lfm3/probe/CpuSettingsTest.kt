package app.lfm3.probe

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.CpuOptions
import app.lfm3.probe.inference.RuntimeBackend
import app.lfm3.probe.ui.TaviSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CpuSettingsTest {
    @Test fun validSettingsPersistAndCorruptValuesRecoverWithoutChangingUserPreferences() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cpu-fixture-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            TaviSettings(context).cpu = CpuOptions(2, 6, 64)
            assertEquals(CpuOptions(2, 6, 64), TaviSettings(context).cpu)
            TaviSettings(context).backend = RuntimeBackend.OPENCL
            assertEquals(if (BuildConfig.OPENCL_COMPILED) RuntimeBackend.OPENCL else RuntimeBackend.CPU, TaviSettings(context).backend)
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putInt("cpu_threads", 999).commit()
            assertEquals(CpuOptions(), TaviSettings(context).cpu)
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("runtime_backend", "unknown").commit()
            assertEquals(RuntimeBackend.CPU, TaviSettings(context).backend)
        } finally { base.deleteSharedPreferences(name) }
    }
}
