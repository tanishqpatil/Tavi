package app.lfm3.probe

import android.app.Application
import app.lfm3.probe.models.RuntimeManager

class TaviApplication : Application() {
    val media by lazy { app.lfm3.probe.media.MediaStore(this) }
    val conversations by lazy { app.lfm3.probe.history.ConversationRepository(this) }
    val runtime by lazy { RuntimeManager(this, media) }
}
