package app.lfm3.probe.inference

/** All stateful calls must be serialized by the engine; cancel is the thread-safe exception. */
object NativeLlama {
    init { System.loadLibrary("tavi") }
    external fun info(): String
    external fun prepare(): Long
    external fun load(path: String)
    external fun unload()
    external fun begin(contents: Array<ByteArray>, roles: Array<String>, ticket: Long)
    external fun next(): ByteArray?
    external fun cancel()
    external fun usage(): LongArray
}
