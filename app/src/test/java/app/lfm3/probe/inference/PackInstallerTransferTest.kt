package app.lfm3.probe.inference

import app.lfm3.probe.models.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class PackInstallerTransferTest {
    private val weights = "abcdef".toByteArray()
    private val license = "license".toByteArray()
    private fun artifact(name: String, bytes: ByteArray) = VerifiedArtifact(name, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "https://huggingface.co/test/model/resolve/${"a".repeat(40)}/$name")
    private val pack = ModelPack("fixture", "Fixture", "a".repeat(40), setOf(ModelCapability.TEXT), artifact("model.gguf", weights), null, artifact("LICENSE", license))
    private fun staging(root: File) = File(root, "${pack.directory}.staging").apply { mkdirs() }

    @Test fun `transfer identity changes with quant components and license even at the same revision`() {
        assertNotEquals(pack.fingerprint, pack.copy(weights = pack.weights.copy(sha256 = "b".repeat(64))).fingerprint)
        assertNotEquals(pack.fingerprint, pack.copy(license = pack.license.copy(sha256 = "c".repeat(64))).fingerprint)
        assertNotEquals(pack.fingerprint, pack.copy(projector = artifact("mmproj.gguf", byteArrayOf(1))).fingerprint)
        assertEquals(pack.fingerprint, pack.copy().fingerprint)
    }

    @Test fun `valid range resumes and activates only a complete verified pack`() = runBlocking {
        val root = Files.createTempDirectory("tavi-range").toFile()
        try {
            File(staging(root), pack.weights.name).writeBytes(weights.copyOfRange(0, 3))
            var requested: String? = null
            val installer = PackInstaller(root, pack = pack) { url ->
                if (url.path.endsWith("LICENSE")) Response(url, 200, license)
                else object : Response(url, 206, weights.copyOfRange(3, 6), "bytes 3-5/6") {
                    override fun getResponseCode(): Int { requested = getRequestProperty("Range"); return super.getResponseCode() }
                }
            }
            installer.install { _, _ -> }
            assertEquals("bytes=3-", requested)
            assertTrue(installer.verifyInstalled())
            assertFalse(staging(root).resolve(pack.weights.name).exists())
            assertArrayEquals(weights, File(installer.installed, pack.weights.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun `ignored range restarts instead of appending a full response`() = runBlocking {
        val root = Files.createTempDirectory("tavi-restart").toFile()
        try {
            File(staging(root), pack.weights.name).writeText("abc")
            val installer = PackInstaller(root, pack = pack) { url -> Response(url, 200, if (url.path.endsWith("LICENSE")) license else weights) }
            installer.install { _, _ -> }
            assertTrue(installer.verifyInstalled())
            assertArrayEquals(weights, File(installer.installed, pack.weights.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun `invalid resume ranges cannot activate a pack`() = runBlocking {
        val root = Files.createTempDirectory("tavi-invalid-range").toFile()
        try {
            File(staging(root), pack.weights.name).writeText("abc")
            val installer = PackInstaller(root, pack = pack) { url -> Response(url, 206, "def".toByteArray(), "bytes 2-4/6") }
            try { installer.install { _, _ -> }; fail("Invalid range accepted") } catch (_: IllegalStateException) { }
            assertFalse(installer.installed.exists())
            assertEquals(3, File(staging(root), pack.weights.name).length())
        } finally { root.deleteRecursively() }
    }

    @Test fun `disconnected transfers retain bytes and a retry resumes them`() = runBlocking {
        val root = Files.createTempDirectory("tavi-interrupted").toFile()
        try {
            val interrupted = PackInstaller(root, pack = pack) { url -> object : Response(url, 200, weights) {
                override fun getInputStream(): InputStream = object : InputStream() {
                    var position = 0
                    override fun read(): Int { if (position == 3) throw IOException("fixture disconnect"); return weights[position++].toInt() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (position == 3) throw IOException("fixture disconnect")
                        val size = minOf(length, 3 - position); weights.copyInto(buffer, offset, position, position + size); position += size; return size
                    }
                }
            } }
            try { interrupted.install { _, _ -> }; fail("Disconnect ignored") } catch (_: IOException) { }
            assertEquals(3, File(staging(root), pack.weights.name).length())
            assertFalse(interrupted.installed.exists())
            val retry = PackInstaller(root, pack = pack) { url -> if (url.path.endsWith("LICENSE")) Response(url, 200, license)
                else Response(url, 206, "def".toByteArray(), "bytes 3-5/6") }
            retry.install { _, _ -> }; assertTrue(retry.verifyInstalled())
        } finally { root.deleteRecursively() }
    }

    @Test fun `checksum failures discard the damaged artifact and do not publish`() = runBlocking {
        val root = Files.createTempDirectory("tavi-bad-hash").toFile()
        try {
            val installer = PackInstaller(root, pack = pack) { url -> Response(url, 200, "xxxxxx".toByteArray()) }
            try { installer.install { _, _ -> }; fail("Corrupt weights accepted") } catch (_: IllegalStateException) { }
            assertFalse(installer.installed.exists())
            assertFalse(File(staging(root), pack.weights.name).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `clean premature EOF also preserves a resumable prefix`() = runBlocking {
        val root = Files.createTempDirectory("tavi-short-body").toFile()
        try {
            val installer = PackInstaller(root, pack = pack) { url -> object : Response(url, 200, "abc".toByteArray()) {
                override fun getContentLengthLong() = -1L
            } }
            try { installer.install { _, _ -> }; fail("Incomplete transfer accepted") } catch (_: EOFException) { }
            assertEquals(3, File(staging(root), pack.weights.name).length())
            assertFalse(installer.installed.exists())
        } finally { root.deleteRecursively() }
    }

    private open class Response(url: URL, private val status: Int, private val bytes: ByteArray, private val range: String? = null) : HttpsURLConnection(url) {
        override fun getResponseCode() = status
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getHeaderField(name: String): String? = if (name == "Content-Range") range else null
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
