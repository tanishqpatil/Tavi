package app.lfm3.probe.ui

import android.content.Context

/** Small preferences only. Conversation content and diagnostics never enter this store. */
class TaviSettings(context: Context) {
    private val preferences = context.getSharedPreferences("tavi_settings", Context.MODE_PRIVATE)
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
