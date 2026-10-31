package app.droplink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droplink.ui.AppRoot
import app.droplink.ui.StartRequest
import app.droplink.ui.theme.DropLinkTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Single activity. AppCompatActivity (not ComponentActivity) so per-app language works on Android 8–12. */
class MainActivity : AppCompatActivity() {
    private val startRequest = MutableStateFlow<StartRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val container = (application as DropLinkApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            DropLinkTheme(settings.theme) {
                AppRoot(container, startRequest) { startRequest.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // A USB cable may have been plugged in while we were in the background.
        (application as DropLinkApp).container.usbLink.rescan()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED ||
            intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
        ) {
            (application as DropLinkApp).container.usbLink.handleIntent(intent)
            return
        }
        startRequest.value = when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val uris = listOfNotNull(uri)
                if (uris.isEmpty() && intent.getStringExtra(Intent.EXTRA_TEXT) != null) StartRequest.UnsupportedText
                else StartRequest.Share(uris)
            }
            Intent.ACTION_SEND_MULTIPLE -> StartRequest.Share(
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty(),
            )
            ACTION_RECEIVE -> StartRequest.Receive
            ACTION_SHOW_TRANSFER -> StartRequest.ShowTransfer
            else -> null
        }
    }

    companion object {
        const val ACTION_RECEIVE = "app.droplink.action.RECEIVE"
        const val ACTION_SHOW_TRANSFER = "app.droplink.action.SHOW_TRANSFER"
    }
}
