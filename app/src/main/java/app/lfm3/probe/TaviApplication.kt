package app.lfm3.probe

import android.app.Application
import app.lfm3.probe.models.RuntimeManager

class TaviApplication : Application() {
    val runtime by lazy { RuntimeManager(this) }
}
