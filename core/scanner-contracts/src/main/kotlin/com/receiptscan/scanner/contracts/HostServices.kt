package com.receiptscan.scanner.contracts

import java.io.File

/**
 * Narrow temporary storage a module may request. The host owns the directory and deletes it
 * when the scan session ends; modules must not write anywhere else.
 */
interface TempStorage {
    fun newTempFile(prefix: String, suffix: String): File
    fun release(file: File)
}

/** What a host lets a module do. A host can switch optional actions off without breaking a scan. */
data class HostCapabilities(
    val canSave: Boolean = true,
    val canShare: Boolean = true,
    val canSetReminders: Boolean = false,
    val canOpenLinks: Boolean = true,
    /** Online lookups (product catalogs) are off unless the host explicitly allows them. */
    val allowOnlineLookups: Boolean = false,
)

/** Readiness of a provider (OCR, translation pack, detector model, speech engine). */
sealed interface ProviderState {
    data object Ready : ProviderState
    data class NeedsDownload(val packId: String, val sizeBytes: Long?) : ProviderState
    data class Downloading(val packId: String, val downloadedBytes: Long, val totalBytes: Long?) : ProviderState
    data class Unavailable(val reason: String) : ProviderState
}
