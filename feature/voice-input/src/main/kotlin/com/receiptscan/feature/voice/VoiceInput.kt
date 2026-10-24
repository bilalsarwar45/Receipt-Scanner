package com.receiptscan.feature.voice

import android.Manifest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.receiptscan.capture.PermissionStatus
import com.receiptscan.capture.rememberPermissionState
import com.receiptscan.design.AppIcons
import com.receiptscan.design.skin
import kotlinx.coroutines.launch

/** States from brief 8.1. The level meter only moves while [Listening]. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data object Checking : VoiceState
    data object Listening : VoiceState
    /** Microphone stopped; the recognizer is finishing the transcript. */
    data object Transcribing : VoiceState
    data class Done(val text: String, val alternatives: List<String>) : VoiceState
    data class Error(val error: SpeechError) : VoiceState
    data class Unavailable(val reason: String, val canDownload: Boolean) : VoiceState
    data object NeedsPermission : VoiceState
}

@Stable
class VoiceController internal constructor() {
    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        internal set
    var partial by mutableStateOf("")
        internal set
    var providerLabel by mutableStateOf<String?>(null)
        internal set
    var onDevice by mutableStateOf(true)
        internal set
    /** Recent input levels, 0..1, newest last. */
    val levels = mutableStateListOf<Float>()
    internal var session: SpeechSession? = null
    internal var startAction: () -> Unit = {}
    internal var downloadAction: () -> Unit = {}

    val isListening get() = state == VoiceState.Listening
    fun start() = startAction()
    fun stop() { session?.stop(); if (state == VoiceState.Listening) state = VoiceState.Transcribing }
    fun cancel() { session?.cancel(); session = null; state = VoiceState.Idle; partial = ""; levels.clear() }
    fun download() = downloadAction()
    fun reset() { cancel() }
}

/**
 * Push-to-talk controller. RECORD_AUDIO is requested on first [VoiceController.start]. Listening
 * stops when the screen leaves the foreground. Raw audio is never stored by this app.
 */
@Composable
fun rememberVoiceController(languageTag: String, allowSystemSpeech: Boolean, micAskedBefore: Boolean, onMicAsked: () -> Unit): VoiceController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { VoiceController() }
    val permission = rememberPermissionState(Manifest.permission.RECORD_AUDIO, micAskedBefore, onMicAsked) { granted -> if (granted) c.start() else c.state = VoiceState.NeedsPermission }

    c.downloadAction = { OnDeviceSpeechProvider.requestDownload(context, languageTag) }
    c.startAction = start@{
        if (c.state == VoiceState.Listening || c.state == VoiceState.Checking) return@start
        when (permission.status) {
            PermissionStatus.GRANTED -> {}
            PermissionStatus.PERMANENTLY_DENIED -> { c.state = VoiceState.NeedsPermission; return@start }
            else -> { permission.request(); return@start }
        }
        c.state = VoiceState.Checking
        scope.launch {
            val chosen = SpeechProviders.choose(context, languageTag, allowSystemSpeech)
            if (chosen == null) {
                c.state = VoiceState.Unavailable("Speech on this phone needs an online service. You can allow it in Settings, or type instead.", false); return@launch
            }
            val (provider, availability) = chosen
            when (availability) {
                is Availability.Unavailable -> { c.state = VoiceState.Unavailable(availability.reason, false); return@launch }
                Availability.NeedsDownload -> { c.state = VoiceState.Unavailable("Download the speech model for this language first.", true); return@launch }
                Availability.Ready -> {}
            }
            c.providerLabel = provider.label
            c.onDevice = provider.onDevice
            c.partial = ""
            c.levels.clear()
            c.state = VoiceState.Listening
            c.session = provider.start(context, languageTag) { e ->
                when (e) {
                    is SpeechEvent.Level -> if (c.state == VoiceState.Listening) {
                        c.levels.add(((e.rmsDb + 2f) / 12f).coerceIn(0f, 1f)); if (c.levels.size > 24) c.levels.removeAt(0)
                    }
                    is SpeechEvent.Partial -> c.partial = e.text
                    SpeechEvent.SpeechEnded -> if (c.state == VoiceState.Listening) c.state = VoiceState.Transcribing
                    is SpeechEvent.Final -> { c.session = null; c.state = VoiceState.Done(e.alternatives.first(), e.alternatives) }
                    is SpeechEvent.Failed -> { c.session = null; c.state = VoiceState.Error(e.error) }
                    else -> {}
                }
            }
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) c.cancel() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); c.cancel() }
    }
    return c
}

/** Large circular push-to-talk button: tap to start, tap again to stop. */
@Composable
fun MicButton(controller: VoiceController, modifier: Modifier = Modifier) {
    val t = skin
    val listening = controller.isListening
    val level = controller.levels.lastOrNull() ?: 0f
    val scale by animateFloatAsState(if (listening) 1f + level * 0.25f else 1f, label = "mic")
    Box(
        modifier.size((72 * scale).dp).clip(CircleShape).background(if (listening) t.err else t.primary)
            .semantics { role = Role.Button; contentDescription = if (listening) "Stop listening" else "Start voice input"; stateDescription = if (listening) "Listening" else "Not listening" }
            .clickable { if (listening) controller.stop() else controller.start() },
        contentAlignment = Alignment.Center,
    ) { Icon(if (listening) AppIcons.stop else AppIcons.mic, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
}

/** Real input-level bars; flat when not listening. */
@Composable
fun LevelBars(controller: VoiceController, modifier: Modifier = Modifier) {
    val t = skin
    val bars = if (controller.isListening) controller.levels.toList() else emptyList()
    Row(modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(24) { i ->
            val v = bars.getOrNull(i - (24 - bars.size)) ?: 0f
            Box(Modifier.width(4.dp).fillMaxHeight(0.1f + v * 0.9f).clip(RoundedCornerShape(2.dp)).background(if (controller.isListening) t.primary else t.border))
        }
    }
}
