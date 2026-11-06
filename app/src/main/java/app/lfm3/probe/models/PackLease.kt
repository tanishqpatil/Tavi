package app.lfm3.probe.models

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** OS-backed lease survives client-state races and is released automatically on process death. */
class PackLease private constructor(private val file: RandomAccessFile, private val lock: FileLock) : Closeable {
    override fun close() { try { lock.release() } finally { file.close() } }
    companion object {
        fun acquire(root: File, pack: ModelPack, shared: Boolean): PackLease {
            root.mkdirs()
            val file = RandomAccessFile(File(root, "${pack.directory}.lease"), "rw")
            try {
                val lock = file.channel.tryLock(0, Long.MAX_VALUE, shared) ?: error("This model pack is currently in use. Stop its operation and retry.")
                return PackLease(file, lock)
            } catch (error: Exception) {
                file.close()
                throw IllegalStateException("This model pack is currently in use. Stop its operation and retry.", error)
            }
        }
    }
}
