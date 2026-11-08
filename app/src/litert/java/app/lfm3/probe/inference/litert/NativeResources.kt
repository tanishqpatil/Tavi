package app.lfm3.probe.inference.litert

/** Registers native allocations before the next operation can fail, including during constructors. */
internal class NativeResources : AutoCloseable {
    private val owned = ArrayList<AutoCloseable>()
    private var closed = false
    fun <T : AutoCloseable> own(value: T): T {
        check(!closed)
        owned.add(value)
        return value
    }
    override fun close() {
        if (closed) return
        closed = true
        owned.asReversed().forEach { runCatching { it.close() } }
        owned.clear()
    }
}
