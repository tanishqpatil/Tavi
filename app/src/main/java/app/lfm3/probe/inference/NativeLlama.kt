package app.lfm3.probe.inference

/** All stateful calls must be serialized by the engine; cancel is the thread-safe exception. */
object NativeLlama {
    init { System.loadLibrary("tavi") }
    external fun info(): String
    external fun prepare(): Long
    external fun load(path: String, contextTokens: Int = 1024, projector: String = "", threads: Int = 4, prefillThreads: Int = 4, batch: Int = 128, opencl: Boolean = false, cacheDirectory: String = "")
    external fun openclAvailable(): Boolean
    external fun unload()
    external fun begin(contents: Array<ByteArray>, roles: Array<String>, ticket: Long, outputLimit: Int = 128, mediaFiles: Array<String> = emptyArray(), speak: Boolean = false, routingJson: Boolean = false)
    external fun next(): ByteArray?
    external fun cancel()
    external fun usage(): LongArray
    external fun audio(): FloatArray?
    external fun audioRate(): Int
    external fun capabilities(): Int
}
