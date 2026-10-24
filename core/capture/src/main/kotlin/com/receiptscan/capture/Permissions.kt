package com.receiptscan.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class PermissionStatus { GRANTED, NOT_ASKED, DENIED, PERMANENTLY_DENIED }

/** Tracks one runtime permission. [request] opens the system dialog; call it only after the pre-prompt. */
class PermissionState internal constructor(
    val permission: String,
    status: PermissionStatus,
    private val launch: () -> Unit,
) {
    var status by mutableStateOf(status)
        internal set
    val granted get() = status == PermissionStatus.GRANTED
    fun request() = launch()
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun currentStatus(context: Context, permission: String, asked: Boolean): PermissionStatus {
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return PermissionStatus.GRANTED
    if (!asked) return PermissionStatus.NOT_ASKED
    val activity = context.findActivity()
    val rationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    return if (rationale) PermissionStatus.DENIED else PermissionStatus.PERMANENTLY_DENIED
}

/**
 * [askedBefore] comes from host preferences so a permanent denial can be told apart from "never asked".
 * [onAsked] lets the host persist that the system dialog was shown.
 */
@Composable
fun rememberPermissionState(permission: String, askedBefore: Boolean, onAsked: () -> Unit = {}, onResult: (Boolean) -> Unit = {}): PermissionState {
    val context = LocalContext.current
    var holder: PermissionState? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        holder?.status = if (granted) PermissionStatus.GRANTED else currentStatus(context, permission, true)
        onResult(granted)
    }
    val state = remember(permission) {
        PermissionState(permission, currentStatus(context, permission, askedBefore)) {
            onAsked()
            launcher.launch(permission)
        }
    }
    holder = state
    // Re-check on every composition: the person may have changed it in Settings.
    val now = currentStatus(context, permission, askedBefore || state.status != PermissionStatus.NOT_ASKED)
    if (now == PermissionStatus.GRANTED && state.status != PermissionStatus.GRANTED) state.status = now
    return state
}

@Composable
fun rememberCameraPermission(askedBefore: Boolean, onAsked: () -> Unit = {}) = rememberPermissionState(Manifest.permission.CAMERA, askedBefore, onAsked)

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Android Photo Picker for one or more images; no storage permission needed. */
@Composable
fun rememberImagePicker(multiple: Boolean = false, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPicked(listOf(uri)) }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    return { if (multiple) many.launch(request) else single.launch(request) }
}

/** Storage Access Framework picker for PDFs and images. */
@Composable
fun rememberDocumentPicker(mimeTypes: Array<String> = arrayOf("application/pdf", "image/*"), onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    return { launcher.launch(mimeTypes) }
}
