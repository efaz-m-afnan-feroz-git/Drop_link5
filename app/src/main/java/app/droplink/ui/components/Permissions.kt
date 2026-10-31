package app.droplink.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import app.droplink.R
import app.droplink.util.Perms

/** One step of a permission flow: a rationale shown before the system prompt. */
data class PermissionAsk(
    val permissions: List<String>,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val icon: ImageVector,
    /** Not a permission: asks the user to switch location services on (Wi-Fi scanning on Android 8–12). */
    val locationSwitch: Boolean = false,
)

object Asks {
    fun nearbyWifi(forReceive: Boolean) = PermissionAsk(
        Perms.nearbyWifi(),
        R.string.perm_nearby_title,
        if (Perms.needsLocationServices()) {
            if (forReceive) R.string.perm_location_body_receive else R.string.perm_location_body_send
        } else {
            if (forReceive) R.string.perm_nearby_body_receive else R.string.perm_nearby_body_send
        },
        Icons.Outlined.Wifi,
    )

    val locationSwitch = PermissionAsk(emptyList(), R.string.perm_location_switch_title, R.string.perm_location_switch_body, Icons.Outlined.LocationOn, locationSwitch = true)
    val camera = PermissionAsk(Perms.camera(), R.string.perm_camera_title, R.string.perm_camera_body, Icons.Outlined.CameraAlt)
    val notifications = PermissionAsk(Perms.notifications(), R.string.perm_notifications_title, R.string.perm_notifications_body, Icons.Outlined.Notifications)
    val legacyStorage = PermissionAsk(Perms.legacyStorage(), R.string.perm_storage_title, R.string.perm_storage_body, Icons.Outlined.Folder)
}

/** Shows a rationale dialog before each system permission prompt, one ask at a time. */
@Stable
class PermissionFlow internal constructor() {
    internal var queue by mutableStateOf<List<PermissionAsk>>(emptyList())
    internal var onDone: (() -> Unit)? = null

    fun run(context: Context, asks: List<PermissionAsk>, onDone: () -> Unit) {
        val needed = asks.filter { ask ->
            if (ask.locationSwitch) Perms.needsLocationServices() && !Perms.locationServicesOn(context)
            else ask.permissions.isNotEmpty() && !Perms.granted(context, ask.permissions)
        }
        if (needed.isEmpty()) {
            onDone()
        } else {
            this.onDone = onDone
            queue = needed
        }
    }

    internal fun advance() {
        queue = queue.drop(1)
        if (queue.isEmpty()) {
            val done = onDone
            onDone = null
            done?.invoke()
        }
    }
}

@Composable
fun rememberPermissionFlow(): PermissionFlow {
    val flow = remember { PermissionFlow() }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        flow.advance()
    }
    val ask = flow.queue.firstOrNull() ?: return flow
    val prefs = remember { context.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    val activity = context.findActivity()
    val permanentlyDenied = !ask.locationSwitch && ask.permissions.any { p ->
        prefs.getBoolean(p, false) && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p) &&
            !Perms.granted(context, listOf(p))
    }

    AlertDialog(
        onDismissRequest = { flow.advance() },
        icon = { Icon(ask.icon, contentDescription = null) },
        title = { Text(stringResource(ask.title)) },
        text = {
            Text(
                stringResource(ask.body) + if (permanentlyDenied) "\n\n" + stringResource(R.string.perm_open_settings_hint) else "",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    ask.locationSwitch -> {
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        flow.advance()
                    }
                    permanentlyDenied -> {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }
                        flow.advance()
                    }
                    else -> {
                        prefs.edit().apply { ask.permissions.forEach { putBoolean(it, true) } }.apply()
                        launcher.launch(ask.permissions.toTypedArray())
                    }
                }
            }) {
                Text(
                    stringResource(
                        when {
                            ask.locationSwitch -> R.string.action_open_location_settings
                            permanentlyDenied -> R.string.action_open_settings
                            else -> R.string.action_continue
                        },
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = { flow.advance() }) { Text(stringResource(R.string.action_not_now)) } },
    )
    return flow
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
