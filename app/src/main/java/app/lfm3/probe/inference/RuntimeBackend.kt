package app.lfm3.probe.inference

enum class RuntimeBackend(val label: String) {
    CPU("CPU"), OPENCL("OpenCL LM / CPU media (experimental)")
}
