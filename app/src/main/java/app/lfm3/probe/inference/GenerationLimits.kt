package app.lfm3.probe.inference

data class GenerationLimits(val context: Int = 1024, val output: Int = 128) {
    init { require(context in contexts); require(output in outputs && output <= context / 2) }
    companion object {
        val contexts = listOf(1024, 2048, 4096, 8192)
        val outputs = listOf(64, 128, 256, 512, 1024, 2048)
    }
}
