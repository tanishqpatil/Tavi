package app.lfm3.probe.inference

import app.lfm3.probe.models.ModelPack
import java.io.File

/** The service owns exactly one loaded session and its pack lease, regardless of graph format. */
internal interface RuntimeSession : InferenceEngine {
    val loaded: Boolean
    suspend fun load(pack: ModelPack, directory: File, context: Int, cpu: CpuOptions, backend: RuntimeBackend)
    suspend fun unload()
    suspend fun dispose() = unload()
}
