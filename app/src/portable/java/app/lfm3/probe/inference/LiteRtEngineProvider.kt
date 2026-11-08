package app.lfm3.probe.inference

import android.content.Context

internal object LiteRtEngineProvider {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): RuntimeSession =
        error("LiteRT requires the experimental lab build.")
}
