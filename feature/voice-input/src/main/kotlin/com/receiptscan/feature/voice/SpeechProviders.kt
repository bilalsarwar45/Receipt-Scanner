package com.receiptscan.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Structured speech errors, mapped to plain-language recovery copy in the UI. */
enum class SpeechError(val message: String) {
    NO_MATCH("We didn't catch that. Try again or type it."),
    NO_SPEECH("We didn't hear anything. Hold the mic and speak."),
    AUDIO("The microphone isn't working. Another app may be using it."),
    PERMISSION("Microphone access is off."),
    NETWORK("This recognizer needs a network connection."),
    BUSY("Speech recognition is busy. Try again in a moment."),
    LANGUAGE("This language isn't available on this phone."),
    MODEL_MISSING("The speech model for this language isn't downloaded yet."),
    OTHER("Speech recognition stopped unexpectedly."),
}

sealed interface Availability {
    data object Ready : Availability
    /** The provider exists but the language model must be downloaded first. */
    data object NeedsDownload : Availability
    data class Unavailable(val reason: String) : Availability
}

/** Events from one listening session. [Level] carries the real input level in dB (roughly -2..10). */
sealed interface SpeechEvent {
    data object ReadyForSpeech : SpeechEvent
    data object SpeechStarted : SpeechEvent
    data class Level(val rmsDb: Float) : SpeechEvent
    data object SpeechEnded : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val alternatives: List<String>) : SpeechEvent
    data class Failed(val error: SpeechError) : SpeechEvent
}

interface SpeechSession {
    /** Stops listening and lets the recognizer finish with what it heard. */
    fun stop()
    /** Discards everything; no Final event follows. */
    fun cancel()
}

/** Narrow provider boundary (brief 8.2). Implementations must run on the main thread. */
interface SpeechToTextProvider {
    val id: String
    val label: String
    /** True only when recognition is guaranteed to stay on the device. */
    val onDevice: Boolean
    suspend fun availability(context: Context, languageTag: String): Availability
    /** Asks the platform to download the model for [languageTag] where supported. */
    fun requestDownload(context: Context, languageTag: String): Boolean = false
    fun start(context: Context, languageTag: String, onEvent: (SpeechEvent) -> Unit): SpeechSession
}

private fun mapError(code: Int): SpeechError = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
    SpeechRecognizer.ERROR_AUDIO -> SpeechError.AUDIO
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.NETWORK
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> SpeechError.LANGUAGE
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.MODEL_MISSING
    else -> SpeechError.OTHER
}

private fun intent(languageTag: String, onDevice: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
}

private class RecognizerSession(private val recognizer: SpeechRecognizer, onEvent: (SpeechEvent) -> Unit) : SpeechSession {
    @Volatile private var finished = false
    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(SpeechEvent.ReadyForSpeech)
            override fun onBeginningOfSpeech() = onEvent(SpeechEvent.SpeechStarted)
            override fun onRmsChanged(rmsdB: Float) { if (!finished) onEvent(SpeechEvent.Level(rmsdB)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onEvent(SpeechEvent.SpeechEnded)
            override fun onError(error: Int) { if (finish()) onEvent(SpeechEvent.Failed(mapError(error))) }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                if (finish()) onEvent(if (list.isEmpty()) SpeechEvent.Failed(SpeechError.NO_MATCH) else SpeechEvent.Final(list))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (!finished) onEvent(SpeechEvent.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    /** Returns true the first time; duplicate final callbacks are ignored. */
    private fun finish(): Boolean { if (finished) return false; finished = true; recognizer.destroy(); return true }
    override fun stop() { if (!finished) recognizer.stopListening() }
    override fun cancel() { if (!finished) { finished = true; recognizer.cancel(); recognizer.destroy() } }
}

/** Android's explicitly on-device recognizer (API 31+). Audio stays on the phone. */
object OnDeviceSpeechProvider : SpeechToTextProvider {
    override val id = "android-on-device"
    override val label = "Android on-device speech"
    override val onDevice = true

    override suspend fun availability(context: Context, languageTag: String): Availability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Availability.Unavailable("Needs Android 12 or newer")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return Availability.Unavailable("This phone has no on-device speech recognizer")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Availability.Ready
        return checkSupport(context, languageTag)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkSupport(context: Context, languageTag: String): Availability = suspendCancellableCoroutine { cont ->
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val main: Executor = ContextCompat.getMainExecutor(context)
        r.checkRecognitionSupport(intent(languageTag, true), main, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                r.destroy()
                val lang = languageTag.lowercase()
                fun List<String>.has() = any { it.lowercase() == lang || it.lowercase().substringBefore('-') == lang.substringBefore('-') }
                val result = when {
                    s.installedOnDeviceLanguages.has() -> Availability.Ready
                    s.pendingOnDeviceLanguages.has() -> Availability.NeedsDownload
                    s.supportedOnDeviceLanguages.has() -> Availability.NeedsDownload
                    else -> Availability.Unavailable("$languageTag isn't supported on-device")
                }
                if (cont.isActive) cont.resume(result)
            }
            override fun onError(error: Int) {
                r.destroy()
                // Some recognizers don't implement the support query; assume usable and surface errors at start.
                if (cont.isActive) cont.resume(Availability.Ready)
            }
        })
        cont.invokeOnCancellation { main.execute { r.destroy() } }
    }

    override fun requestDownload(context: Context, languageTag: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try { r.triggerModelDownload(intent(languageTag, true)); true } catch (_: Exception) { false } finally { r.destroy() }
    }

    override fun start(context: Context, languageTag: String, onEvent: (SpeechEvent) -> Unit): SpeechSession {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val s = RecognizerSession(r, onEvent)
        r.startListening(intent(languageTag, true))
        return s
    }
}

/**
 * The phone's default recognizer. It may send audio to its provider's servers, so the app only uses
 * it after the person turns on "Allow system speech service" in Settings.
 */
object SystemSpeechProvider : SpeechToTextProvider {
    override val id = "android-system"
    override val label = "System speech service (may use the internet)"
    override val onDevice = false

    override suspend fun availability(context: Context, languageTag: String): Availability =
        if (SpeechRecognizer.isRecognitionAvailable(context)) Availability.Ready else Availability.Unavailable("No speech service is installed")

    override fun start(context: Context, languageTag: String, onEvent: (SpeechEvent) -> Unit): SpeechSession {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        val s = RecognizerSession(r, onEvent)
        r.startListening(intent(languageTag, false))
        return s
    }
}

/** Picks the provider: on-device first; the system service only with explicit consent. */
object SpeechProviders {
    suspend fun choose(context: Context, languageTag: String, allowSystem: Boolean): Pair<SpeechToTextProvider, Availability>? {
        val local = OnDeviceSpeechProvider.availability(context, languageTag)
        if (local !is Availability.Unavailable) return OnDeviceSpeechProvider to local
        if (allowSystem) return SystemSpeechProvider to SystemSpeechProvider.availability(context, languageTag)
        return null
    }

    suspend fun onDeviceStatus(context: Context, languageTag: String) = OnDeviceSpeechProvider.availability(context, languageTag)
}
