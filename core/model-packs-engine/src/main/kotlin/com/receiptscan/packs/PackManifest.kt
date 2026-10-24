package com.receiptscan.packs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A versioned, downloadable model or data pack. Runtime code is never downloaded: packs contain
 * weights, label maps and data only, and are verified by SHA-256 before use.
 */
@Serializable
data class PackSpec(
    val id: String,
    val purpose: String,
    val version: String,
    val provider: String,
    /** Immutable HTTPS URL of the exact artifact. */
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** "file" for a single file, "zip" for an archive extracted into the pack directory. */
    val format: String = "file",
    val fileName: String,
    val runtime: String,
    val abis: List<String> = emptyList(),
    val license: String,
    val licenseUrl: String,
    val provenance: String,
    /** Label map / tokenizer / normalisation compatibility the app code expects. */
    val compatibility: Map<String, String> = emptyMap(),
    /** Platform-managed providers (e.g. ML Kit translation models) are listed but not managed here. */
    val platformManaged: Boolean = false,
) {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(id)) { "Bad pack id $id" }
        require(platformManaged || url.startsWith("https://")) { "Pack URLs must be HTTPS" }
        require(platformManaged || Regex("[0-9a-f]{64}").matches(sha256)) { "Pack $id needs a SHA-256 digest" }
        require(!fileName.contains('/') && !fileName.contains("..")) { "Bad file name" }
    }

    /** Free space needed: the download plus room to extract or copy atomically. */
    val requiredFreeBytes: Long get() = if (format == "zip") sizeBytes * 3 else sizeBytes * 2
}

@Serializable
data class PackCatalog(val schema: Int, val packs: List<PackSpec>)

object PackCatalogs {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): PackCatalog = json.decodeFromString(PackCatalog.serializer(), text).also {
        require(it.schema == 1) { "Unsupported catalog schema ${it.schema}" }
        require(it.packs.map { p -> p.id }.toSet().size == it.packs.size) { "Duplicate pack ids" }
    }
}

sealed interface PackState {
    data object NotDownloaded : PackState
    data class Downloading(val downloaded: Long, val total: Long) : PackState
    data object Paused : PackState
    data object Verifying : PackState
    data class Ready(val version: String, val sizeOnDisk: Long) : PackState
    data class Failed(val reason: String, val retryable: Boolean) : PackState
    data class Unavailable(val reason: String) : PackState
    data class NeedsSpace(val requiredBytes: Long, val freeBytes: Long) : PackState
}
