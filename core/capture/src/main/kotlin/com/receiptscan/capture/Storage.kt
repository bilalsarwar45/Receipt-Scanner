package com.receiptscan.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.StatFs
import androidx.core.content.FileProvider
import com.receiptscan.scanner.contracts.TempStorage
import java.io.File
import java.util.UUID

/** Temporary files for one scan session in the app cache. The host deletes them when the session ends. */
class SessionTempStorage(context: Context) : TempStorage {
    private val dir = File(context.cacheDir, "scan-sessions/${UUID.randomUUID()}").apply { mkdirs() }
    override fun newTempFile(prefix: String, suffix: String): File = File.createTempFile(prefix, suffix, dir)
    override fun release(file: File) { if (file.parentFile == dir) file.delete() }
    fun clear() { dir.deleteRecursively() }

    companion object {
        /** Removes sessions left behind by a crash or process death. */
        fun sweep(context: Context) { File(context.cacheDir, "scan-sessions").listFiles()?.forEach { it.deleteRecursively() } }
    }
}

/** Source images the host keeps (receipt photos, scan evidence). App-private, never shared automatically. */
class ImageStore(private val context: Context) {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }

    fun fileFor(id: String) = File(dir, "$id.jpg")

    suspend fun save(bitmap: Bitmap, id: String = UUID.randomUUID().toString()): String {
        Images.saveJpeg(bitmap, fileFor(id), 88)
        return id
    }

    fun delete(id: String) { fileFor(id).delete() }

    fun exists(id: String) = fileFor(id).exists()

    /** content:// URI for sharing one image through the app's FileProvider. */
    fun shareUri(id: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", fileFor(id))

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        /** Free bytes on the app's data partition, used for the "storage full" state. */
        fun freeBytes(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes
    }
}
