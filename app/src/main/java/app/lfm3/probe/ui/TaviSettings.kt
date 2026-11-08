package app.lfm3.probe.ui

import android.content.Context

/** Small preferences only. Conversation content and diagnostics never enter this store. */
class TaviSettings(context: Context) {
    private val preferences = context.getSharedPreferences("tavi_settings", Context.MODE_PRIVATE)
    var unmeteredDownloads: Boolean
        get() = preferences.getBoolean("unmetered_downloads", true)
        set(value) { preferences.edit().putBoolean("unmetered_downloads", value).apply() }
    var limits: app.lfm3.probe.inference.GenerationLimits
        get() = runCatching { app.lfm3.probe.inference.GenerationLimits(preferences.getInt("context", 1024), preferences.getInt("output", 128)) }.getOrDefault(app.lfm3.probe.inference.GenerationLimits())
        set(value) { preferences.edit().putInt("context", value.context).putInt("output", value.output).apply() }
    var cpu: app.lfm3.probe.inference.CpuOptions
        get() = runCatching { app.lfm3.probe.inference.CpuOptions(preferences.getInt("cpu_threads", 4), preferences.getInt("prefill_threads", 4), preferences.getInt("prefill_batch", 128)) }.getOrDefault(app.lfm3.probe.inference.CpuOptions())
        set(value) { preferences.edit().putInt("cpu_threads", value.threads).putInt("prefill_threads", value.prefillThreads).putInt("prefill_batch", value.batch).apply() }
    var theme: ThemeChoice
        get() = ThemeChoice.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeChoice.SYSTEM
        set(value) { preferences.edit().putString("theme", value.name).apply() }
    var developer: Boolean
        get() = preferences.getBoolean("developer", false)
        set(value) { preferences.edit().putBoolean("developer", value).apply() }
    var reduceMotion: Boolean
        get() = preferences.getBoolean("reduce_motion", false)
        set(value) { preferences.edit().putBoolean("reduce_motion", value).apply() }
}
