package com.receiptscan.scanner.contracts

/** Stable capability identifier, e.g. "receipt", "qr". Modules own their id; hosts treat it as opaque. */
@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(Regex("[a-z][a-z0-9-]{1,31}").matches(value)) { "Invalid capability id: $value" }
    }
    override fun toString() = value
}

/** Where the scanner should take its input from. URIs are strings so this contract stays platform neutral. */
sealed interface ScanSource {
    data object Camera : ScanSource
    data class Image(val uri: String) : ScanSource
    data class Pdf(val uri: String) : ScanSource
    /** Text already recognised elsewhere (for example Text → Translate hand-off). */
    data class Text(val text: String, val languageTag: String? = null) : ScanSource
}

/** Something the person should check before saving. [field] names the result field it refers to. */
data class Issue(val code: String, val message: String, val field: String? = null)

/** Why a scan produced no usable result. Each maps to a recovery state in the UI. */
enum class FailReason {
    NO_TEXT,
    BLURRY,
    UNSUPPORTED,
    PERMISSION_DENIED,
    PACK_MISSING,
    PROVIDER_UNAVAILABLE,
    STORAGE_FULL,
    UNREADABLE_FILE,
    CANCELLED,
}

/**
 * The typed completion of a scanner. [R] is a payload type owned by the scanner module,
 * so a host depends only on the scanners it actually embeds.
 */
sealed interface ScanOutcome<out R> {
    data class Success<R>(val result: R) : ScanOutcome<R>
    data class NeedsReview<R>(val result: R, val issues: List<Issue>) : ScanOutcome<R>
    data class Failed(val reason: FailReason, val detail: String? = null) : ScanOutcome<Nothing>
}

/**
 * Neutral, versioned envelope a host can persist for any scanner result it does not
 * understand structurally (Library → Other scans). The payload is serialized by the module.
 */
data class ScanEnvelope(
    val capabilityId: CapabilityId,
    val payloadVersion: Int,
    val title: String,
    val summary: String,
    val payloadJson: String,
    val sourceIds: List<String> = emptyList(),
)

/** A result type that can be stored generically by a host. */
interface EnvelopeEncodable {
    fun toEnvelope(): ScanEnvelope
}
