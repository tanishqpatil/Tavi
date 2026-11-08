package app.lfm3.probe.inference

/** Bounded allocation/parallelism choices. Changes apply only at the next explicit load. */
data class CpuOptions(val threads: Int = 4, val prefillThreads: Int = 4, val batch: Int = 128) {
    init {
        require(threads in threadChoices && prefillThreads in threadChoices) { "Unsupported CPU thread count." }
        require(batch in batchChoices) { "Unsupported prefill batch size." }
    }
    companion object {
        val threadChoices = listOf(1, 2, 4, 6, 8)
        val batchChoices = listOf(64, 128, 256)
    }
}
