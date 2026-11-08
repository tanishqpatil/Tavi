package app.lfm3.probe.inference

import android.content.Context
import app.lfm3.probe.inference.litert.LiteRtEngine

internal object LiteRtEngineProvider {
    fun create(context: Context): RuntimeSession = LiteRtEngine(context)
}
