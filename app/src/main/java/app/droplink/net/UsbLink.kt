package app.droplink.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.droplink.core.transfer.FixedChunkInputStream
import app.droplink.core.transfer.UsbBridge
import app.droplink.util.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed class UsbState {
    data object NotConnected : UsbState()
    /** Cable seen, waiting for the user to allow USB access (system dialog). */
    data object NeedsPermission : UsbState()
    data object Connecting : UsbState()
    /** [peer] is null until the other phone's DropLink answers. */
    data class Connected(val asHost: Boolean, val peer: UsbBridge.Peer?) : UsbState()
    data class Failed(val reason: String) : UsbState()
}

/**
 * USB cable mode on Android, using Android Open Accessory (AOA). Whichever phone ends up as USB
 * host switches the other one into accessory mode; both then exchange bytes over two bulk
 * endpoints, which [UsbBridge] turns into ordinary DropLink connections.
 */
class UsbLink(context: Context, private val bridge: UsbBridge) {
    private val appContext = context.applicationContext
    private val usb: UsbManager? = appContext.getSystemService(UsbManager::class.java)
    private val _state = MutableStateFlow<UsbState>(UsbState.NotConnected)
    val state: StateFlow<UsbState> = _state.asStateFlow()

    @Volatile private var hostDevice: UsbDevice? = null
    @Volatile private var hostConn: UsbDeviceConnection? = null
    @Volatile private var accessoryFd: ParcelFileDescriptor? = null
    private val asked = HashSet<String>() // devices we already asked about (don't nag)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var started = false

    init {
        bridge.onPeer = { peer ->
            val s = _state.value
            _state.value = when {
                s is UsbState.Connected -> s.copy(peer = peer)
                peer != null -> UsbState.Connected(hostDevice != null, peer)
                else -> s
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let { d ->
                        // For a phone that just switched to accessory mode, Android shows its own
                        // "Open DropLink?" dialog (manifest filter); give it a moment before asking ourselves.
                        if (isAccessoryMode(d)) mainHandler.postDelayed({ onDevice(d, userAction = true) }, 2_000)
                        else onDevice(d, userAction = true)
                    }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (d != null && d.deviceName == hostDevice?.deviceName) closeAll("cable unplugged")
                    asked.remove(d?.deviceName)
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> closeAll("cable unplugged")
                ACTION_PERMISSION -> {
                    IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let { onDevice(it, userAction = false) }
                    IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)?.let { onAccessory(it) }
                    if (_state.value == UsbState.NeedsPermission) rescan()
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (started || usb == null) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        rescan()
    }

    /** Checks for a cable that is already plugged in (app start, screen resume). */
    fun rescan() {
        val m = usb ?: return
        if (bridge.isAttached) return
        runCatching { m.accessoryList }.getOrNull()?.firstOrNull()?.let { onAccessory(it); return }
        runCatching { m.deviceList.values.toList() }.getOrDefault(emptyList()).forEach { onDevice(it, userAction = false) }
    }

    /** MainActivity forwards USB attach intents (the system launches us for our accessory/device filters). */
    fun handleIntent(intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED ->
                IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)?.let { onAccessory(it) }
            UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let { onDevice(it, userAction = true) }
        }
    }

    private fun permissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(appContext, 7, Intent(ACTION_PERMISSION).setPackage(appContext.packageName), flags)
    }

    // ------------------------------------------------------------------------------------ host

    private fun isAccessoryMode(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in AOA_PIDS

    /** Phones show up as MTP/PTP (class 6), vendor-specific (0xFF, ADB/MTP), or with no data interface. */
    private fun looksLikePhone(d: UsbDevice): Boolean {
        if (d.interfaceCount == 0) return true
        val classes = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }
        val blocked = setOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_MASS_STORAGE, UsbConstants.USB_CLASS_AUDIO, UsbConstants.USB_CLASS_HUB, UsbConstants.USB_CLASS_PRINTER, UsbConstants.USB_CLASS_VIDEO)
        if (classes.all { it in blocked }) return false
        return classes.any { it == UsbConstants.USB_CLASS_STILL_IMAGE || it == UsbConstants.USB_CLASS_VENDOR_SPEC || it == UsbConstants.USB_CLASS_COMM || it == UsbConstants.USB_CLASS_CDC_DATA || it == UsbConstants.USB_CLASS_MISC }
    }

    @Volatile private var opening = false

    private fun onDevice(d: UsbDevice, userAction: Boolean) {
        val m = usb ?: return
        if (bridge.isAttached || opening) return
        if (!isAccessoryMode(d) && !looksLikePhone(d)) return
        if (!m.hasPermission(d)) {
            if (d.deviceName in asked && !userAction && !isAccessoryMode(d)) return
            asked += d.deviceName
            _state.value = UsbState.NeedsPermission
            DiagLog.log("USB: asking permission for ${describe(d)}")
            runCatching { m.requestPermission(d, permissionIntent()) }
            return
        }
        opening = true
        Thread({
            try {
                if (isAccessoryMode(d)) openAccessoryDevice(d) else switchToAccessory(d)
            } catch (e: Exception) {
                DiagLog.log("USB: host setup failed for ${describe(d)}", e)
                _state.value = UsbState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                opening = false
            }
        }, "droplink-usb-host").start()
    }

    /** AOA handshake: ask the other phone to restart its USB port in accessory mode. */
    private fun switchToAccessory(d: UsbDevice) {
        val m = usb ?: return
        _state.value = UsbState.Connecting
        val conn = m.openDevice(d) ?: throw IOException("Couldn't open the USB device")
        try {
            val buf = ByteArray(2)
            val n = conn.controlTransfer(0xC0, AOA_GET_PROTOCOL, 0, 0, buf, 2, 1_000)
            val version = if (n == 2) (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) else 0
            DiagLog.log("USB: ${describe(d)} AOA protocol $version")
            if (version < 1) {
                _state.value = UsbState.Failed("The other device doesn't support phone-to-phone USB")
                return
            }
            AOA_STRINGS.forEachIndexed { index, value ->
                val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
                conn.controlTransfer(0x40, AOA_SEND_STRING, 0, index, bytes, bytes.size, 1_000)
            }
            conn.controlTransfer(0x40, AOA_START, 0, 0, null, 0, 1_000)
            DiagLog.log("USB: asked ${describe(d)} to switch to accessory mode")
            // It now disconnects and comes back as 18d1:2d0x → ACTION_USB_DEVICE_ATTACHED → openAccessoryDevice.
        } finally {
            conn.close()
        }
    }

    private fun openAccessoryDevice(d: UsbDevice) {
        val m = usb ?: return
        _state.value = UsbState.Connecting
        val conn = m.openDevice(d) ?: throw IOException("Couldn't open the accessory")
        val iface = d.getInterface(0)
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
        }
        if (inEp == null || outEp == null || !conn.claimInterface(iface, true)) {
            conn.close()
            throw IOException("Accessory has no usable bulk endpoints")
        }
        hostDevice = d
        hostConn = conn
        val streams = HostStreams(conn, inEp, outEp)
        DiagLog.log("USB: connected as host to ${describe(d)} (packet ${inEp.maxPacketSize} bytes)")
        _state.value = UsbState.Connected(asHost = true, peer = null)
        bridge.attach(streams.input, streams.output) { closeHost(conn) }
    }

    private fun closeHost(conn: UsbDeviceConnection) {
        if (hostConn === conn) {
            hostConn = null
            hostDevice = null
        }
        runCatching { conn.close() }
        if (!bridge.isAttached) _state.value = UsbState.NotConnected
    }

    /** Blocking streams over bulk endpoints. Reads always ask for full, packet-aligned buffers. */
    private class HostStreams(
        private val conn: UsbDeviceConnection,
        private val inEp: UsbEndpoint,
        private val outEp: UsbEndpoint,
    ) {
        // Android 9+ accepts large bulk transfers; older versions cap a single call at 16 KiB.
        private val maxChunk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 128 * 1024 else 16 * 1024

        // Timeout 0 = wait as long as needed. A timed-out bulk transfer may already have moved part
        // of the data, so retrying could duplicate or lose bytes; unplugging ends a blocked call.
        val input: InputStream = FixedChunkInputStream({ buf ->
            var n: Int
            do {
                n = conn.bulkTransfer(inEp, buf, buf.size, 0)
            } while (n == 0) // zero-length packet
            if (n < 0) throw IOException("USB cable disconnected")
            n
        }, maxChunk)

        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var pos = off
                val end = off + len
                while (pos < end) {
                    val n = conn.bulkTransfer(outEp, b, pos, minOf(maxChunk, end - pos), 0)
                    if (n <= 0) throw IOException("USB write failed")
                    pos += n
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- accessory

    private fun onAccessory(a: UsbAccessory) {
        val m = usb ?: return
        if (bridge.isAttached) return
        if (a.manufacturer != AOA_STRINGS[0] || a.model != AOA_STRINGS[1]) return
        if (!m.hasPermission(a)) {
            _state.value = UsbState.NeedsPermission
            runCatching { m.requestPermission(a, permissionIntent()) }
            return
        }
        try {
            _state.value = UsbState.Connecting
            val fd = m.openAccessory(a) ?: throw IOException("Couldn't open the USB accessory")
            accessoryFd = fd
            val inStream = FileInputStream(fd.fileDescriptor)
            // The accessory driver drops bytes if a read asks for less than one 16 KiB request.
            val input = FixedChunkInputStream({ buf -> inStream.read(buf, 0, buf.size) }, ACCESSORY_READ)
            val output = FileOutputStream(fd.fileDescriptor)
            DiagLog.log("USB: connected as accessory")
            _state.value = UsbState.Connected(asHost = false, peer = null)
            bridge.attach(input, output) {
                runCatching { fd.close() }
                if (accessoryFd === fd) accessoryFd = null
                if (!bridge.isAttached) _state.value = UsbState.NotConnected
            }
        } catch (e: Exception) {
            DiagLog.log("USB: accessory setup failed", e)
            _state.value = UsbState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun closeAll(reason: String) {
        DiagLog.log("USB: closing ($reason)")
        bridge.detach()
        hostConn?.let { runCatching { it.close() } }
        hostConn = null
        hostDevice = null
        accessoryFd?.let { runCatching { it.close() } }
        accessoryFd = null
        _state.value = UsbState.NotConnected
    }

    private fun describe(d: UsbDevice) =
        "%04x:%04x %s".format(d.vendorId, d.productId, runCatching { d.productName }.getOrNull() ?: "")

    companion object {
        private const val ACTION_PERMISSION = "app.droplink.USB_PERMISSION"
        private const val GOOGLE_VID = 0x18D1
        private val AOA_PIDS = 0x2D00..0x2D05
        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_STRING = 52
        private const val AOA_START = 53
        private const val ACCESSORY_READ = 16 * 1024

        /** manufacturer, model, description, version, URI, serial; must match res/xml/usb_accessory_filter.xml */
        val AOA_STRINGS = listOf("DropLink", "DropLink", "DropLink file transfer", "1", "https://github.com", "droplink")
    }
}
