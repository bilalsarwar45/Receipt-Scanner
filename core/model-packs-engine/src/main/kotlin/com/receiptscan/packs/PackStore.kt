package com.receiptscan.packs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Where bytes come from; the HTTPS implementation supports resuming with Range requests. */
fun interface ByteSource {
    /** Opens the stream starting at [offset]. Returns the stream and the total size if known. */
    fun open(url: String, offset: Long): Pair<InputStream, Long?>
}

class HttpsByteSource(private val timeoutMillis: Int = 20_000) : ByteSource {
    override fun open(url: String, offset: Long): Pair<InputStream, Long?> {
        require(url.startsWith("https://"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMillis
        c.readTimeout = timeoutMillis
        c.instanceFollowRedirects = true
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        val code = c.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        if (offset > 0 && code != 206) throw IOException("Server does not support resume")
        val len = c.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }?.let { it + offset }
        return c.inputStream to len
    }
}

class PackVerificationException(message: String) : IOException(message)

/**
 * Installs packs atomically under [root]/<id>/<version>/ and keeps the previous version until the
 * new one verifies, so a failed upgrade rolls back to the working pack.
 */
class PackStore(private val root: File, private val source: ByteSource, private val freeSpace: () -> Long = { root.usableSpace }) {

    private fun packDir(id: String) = File(root, id)
    private fun versionDir(spec: PackSpec) = File(packDir(spec.id), spec.version)
    private fun partial(spec: PackSpec) = File(packDir(spec.id), ".download-${spec.version}.part")

    fun state(spec: PackSpec): PackState {
        if (spec.platformManaged) return PackState.Unavailable("Managed by Android")
        val dir = versionDir(spec)
        val marker = File(dir, ".verified")
        if (marker.exists()) return PackState.Ready(spec.version, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        val part = partial(spec)
        return if (part.exists()) PackState.Paused else PackState.NotDownloaded
    }

    /** The installed file for a ready pack, or null. */
    fun file(spec: PackSpec): File? = File(versionDir(spec), spec.fileName).takeIf { File(versionDir(spec), ".verified").exists() && it.exists() }

    fun installedVersions(id: String): List<String> = packDir(id).listFiles { f -> f.isDirectory && File(f, ".verified").exists() }?.map { it.name }.orEmpty()

    /**
     * Downloads (resuming a partial file), verifies the digest and installs. [onProgress] gets
     * (downloaded, total). [isCancelled] is polled between chunks; cancelling keeps the partial file.
     */
    fun install(spec: PackSpec, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): PackState {
        if (spec.platformManaged) return PackState.Unavailable("Managed by Android")
        state(spec).let { if (it is PackState.Ready) return it }
        val free = freeSpace()
        if (free < spec.requiredFreeBytes) return PackState.NeedsSpace(spec.requiredFreeBytes, free)
        packDir(spec.id).mkdirs()
        val part = partial(spec)
        try {
            var offset = if (part.exists()) part.length() else 0L
            if (offset > spec.sizeBytes) { part.delete(); offset = 0 }
            if (offset < spec.sizeBytes) {
                val (input, _) = try { source.open(spec.url, offset) } catch (e: IOException) {
                    if (offset > 0 && e.message?.contains("resume") == true) { part.delete(); offset = 0; source.open(spec.url, 0) } else throw e
                }
                input.use { inp ->
                    java.io.FileOutputStream(part, offset > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = offset
                        while (true) {
                            if (isCancelled()) return PackState.Paused
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > spec.sizeBytes) throw PackVerificationException("Download is larger than expected")
                            onProgress(done, spec.sizeBytes)
                        }
                    }
                }
            }
            val digest = sha256(part)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                return PackState.Failed("The download was damaged (checksum mismatch). Try again.", retryable = true)
            }
            val staging = File(packDir(spec.id), ".staging-${spec.version}")
            staging.deleteRecursively()
            staging.mkdirs()
            if (spec.format == "zip") safeUnzip(part, staging) else part.copyTo(File(staging, spec.fileName), overwrite = true)
            File(staging, ".verified").writeText(spec.sha256)
            val target = versionDir(spec)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Could not move pack into place")
            part.delete()
            // Keep only the new version; older ones are removed after the new one is in place.
            packDir(spec.id).listFiles()?.filter { it.isDirectory && it.name != spec.version && !it.name.startsWith(".") }?.forEach { it.deleteRecursively() }
            return state(spec)
        } catch (e: PackVerificationException) {
            part.delete()
            return PackState.Failed(e.message ?: "Verification failed", retryable = true)
        } catch (e: IOException) {
            return PackState.Failed("Download stopped: ${e.message}. You can resume.", retryable = true)
        }
    }

    /** Deletes every installed version and partial download of a pack. */
    fun delete(id: String): Boolean = packDir(id).deleteRecursively()

    fun storageReport(specs: List<PackSpec>): Map<String, Long> = specs.associate { s -> s.id to (packDir(s.id).takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L) }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Extracts a zip refusing absolute paths, "..", symlink-like names and oversized expansions. */
        fun safeUnzip(zip: File, into: File, maxTotalBytes: Long = 2L shl 30) {
            val canonicalRoot = into.canonicalFile
            var total = 0L
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name
                    if (name.startsWith("/") || name.contains("..") || name.contains('\\') || name.contains(':')) throw PackVerificationException("Unsafe path in archive: $name")
                    val out = File(into, name).canonicalFile
                    if (!out.path.startsWith(canonicalRoot.path + File.separator)) throw PackVerificationException("Unsafe path in archive: $name")
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    out.parentFile.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zin.read(buf); if (n < 0) break
                            total += n
                            if (total > maxTotalBytes) throw PackVerificationException("Archive expands too much")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }
}
