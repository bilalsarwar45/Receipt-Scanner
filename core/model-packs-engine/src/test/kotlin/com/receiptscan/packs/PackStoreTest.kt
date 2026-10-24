package com.receiptscan.packs

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun spec(bytes: ByteArray, digest: String = sha(bytes), format: String = "file") = PackSpec(
        id = "test-pack", purpose = "test", version = "1", provider = "test", url = "https://example.invalid/p", sha256 = digest,
        sizeBytes = bytes.size.toLong(), format = format, fileName = "model.bin", runtime = "none", license = "test", licenseUrl = "https://example.invalid", provenance = "test",
    )

    @Test fun `downloads verifies and installs atomically`() {
        val store = PackStore(tmp.root, { _, off -> ByteArrayInputStream(payload, off.toInt(), payload.size - off.toInt()) to payload.size.toLong() })
        val s = spec(payload)
        assertThat(store.state(s)).isEqualTo(PackState.NotDownloaded)
        val r = store.install(s)
        assertThat(r).isInstanceOf(PackState.Ready::class.java)
        assertThat(store.file(s)!!.readBytes()).isEqualTo(payload)
    }

    @Test fun `corrupt download is rejected and not installed`() {
        val bad = payload.copyOf().also { it[10] = 99 }
        val store = PackStore(tmp.root, { _, off -> ByteArrayInputStream(bad, off.toInt(), bad.size - off.toInt()) to null })
        val r = store.install(spec(payload))
        assertThat(r).isInstanceOf(PackState.Failed::class.java)
        assertThat(store.file(spec(payload))).isNull()
    }

    @Test fun `interrupted download resumes from the partial file`() {
        var calls = 0
        val src = ByteSource { _, off ->
            calls++
            if (calls == 1) {
                val half = payload.copyOfRange(0, 100_000)
                object : java.io.InputStream() {
                    var i = 0
                    override fun read(): Int = if (i < half.size) half[i++].toInt() and 0xff else throw IOException("connection reset")
                    override fun read(b: ByteArray, o: Int, l: Int): Int { if (i >= half.size) throw IOException("connection reset"); val n = minOf(l, half.size - i); System.arraycopy(half, i, b, o, n); i += n; return n }
                } to null
            } else {
                assertThat(off).isEqualTo(100_000L)
                ByteArrayInputStream(payload, off.toInt(), payload.size - off.toInt()) to null
            }
        }
        val store = PackStore(tmp.root, src)
        assertThat(store.install(spec(payload))).isInstanceOf(PackState.Failed::class.java)
        assertThat(store.state(spec(payload))).isEqualTo(PackState.Paused)
        assertThat(store.install(spec(payload))).isInstanceOf(PackState.Ready::class.java)
    }

    @Test fun `not enough space is reported before downloading`() {
        val store = PackStore(tmp.root, { _, _ -> error("should not download") }, freeSpace = { 10 })
        assertThat(store.install(spec(payload))).isInstanceOf(PackState.NeedsSpace::class.java)
    }

    @Test fun `zip path traversal is refused`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry() }
        val zip = bos.toByteArray()
        val store = PackStore(tmp.root, { _, off -> ByteArrayInputStream(zip, off.toInt(), zip.size - off.toInt()) to null })
        val r = store.install(spec(zip, format = "zip"))
        assertThat(r).isInstanceOf(PackState.Failed::class.java)
        assertThat(java.io.File(tmp.root, "evil.txt").exists()).isFalse()
    }

    @Test fun `manifest validation`() {
        val ok = PackCatalogs.parse("""{"schema":1,"packs":[{"id":"pack-a","purpose":"p","version":"1","provider":"x","url":"https://x/y","sha256":"${"0".repeat(64)}","sizeBytes":1,"fileName":"f","runtime":"r","license":"l","licenseUrl":"https://l","provenance":"p"}]}""")
        assertThat(ok.packs).hasSize(1)
        try { spec(payload).copy(url = "http://insecure"); error("expected") } catch (e: IllegalArgumentException) { }
    }
}
