package app.droplink.core.protocol

/** The ways two phones can reach each other, fastest first. */
enum class ConnectionMode { USB, LAN, WIFI_DIRECT, QR_HOTSPOT }

/** What the sender currently knows about a chosen peer and about its own phone. */
data class ModeInputs(
    /** The user forced a mode in the UI (null = automatic). */
    val forced: ConnectionMode? = null,
    /** The peer was discovered via NSD on the shared Wi-Fi network. */
    val peerOnLan: Boolean,
    /** The peer was seen in Wi-Fi Direct discovery. */
    val peerOnWifiDirect: Boolean,
    /** This phone has a working Wi-Fi Direct stack and the needed permission. */
    val wifiDirectUsable: Boolean,
    /** The peer advertised its own direct (Wi-Fi Direct) network that we can join like normal Wi-Fi. */
    val peerHasDirectNetwork: Boolean = false,
    /** The peer is on the other end of a USB cable (fastest of all). */
    val peerOnUsb: Boolean = false,
)

/**
 * Auto-selection policy (fastest first):
 *  0. a USB cable between the phones → USB (10+ MB/s even on phones with slow Wi-Fi);
 *  1. the receiver advertised its own direct network → join it (phone → phone, no router, 5 GHz);
 *  2. both phones see each other on the LAN → LAN (through the router: slower);
 *  3. Wi-Fi Direct invitation (only for peers without a joinable network);
 *  4. finally the QR code, which needs no shared network at all.
 * A forced mode is honoured on its own (no silent fallback: the user asked for it).
 */
object ModeSelector {
    const val WIFI_DIRECT_TIMEOUT_MS = 15_000L
    const val WIFI_DIRECT_ATTEMPTS = 2
    const val LAN_CONNECT_TIMEOUT_MS = 8_000

    fun plan(inputs: ModeInputs): List<ConnectionMode> {
        inputs.forced?.let { return listOf(it) }
        val plan = ArrayList<ConnectionMode>(4)
        if (inputs.peerOnUsb) plan += ConnectionMode.USB
        if (inputs.peerHasDirectNetwork) plan += ConnectionMode.WIFI_DIRECT
        if (inputs.peerOnLan) plan += ConnectionMode.LAN
        // A Wi-Fi Direct invitation needs the peer's P2P device address, so only when we saw it there.
        if (!inputs.peerHasDirectNetwork && inputs.wifiDirectUsable && inputs.peerOnWifiDirect) plan += ConnectionMode.WIFI_DIRECT
        plan += ConnectionMode.QR_HOTSPOT
        return plan
    }

    /** The next mode to try after [failed] failed, or null if nothing is left. */
    fun next(plan: List<ConnectionMode>, failed: ConnectionMode): ConnectionMode? {
        val i = plan.indexOf(failed)
        return if (i < 0 || i + 1 >= plan.size) null else plan[i + 1]
    }

    /**
     * Whether the receiver should switch its QR hotspot on by default. Without a shared Wi-Fi network
     * the hotspot is the most reliable way in; on Wi-Fi, LAN discovery usually wins, and starting a
     * hotspot can knock some phones off their network, so it stays opt-in.
     */
    fun receiverPrefersQr(connectedToWifi: Boolean): Boolean = !connectedToWifi
}
