package app.droplink.core.protocol

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Everything a sender needs to join the receiver's local-only hotspot and pin its certificate.
 * Encoded as `droplink://join?v=1&s=<ssid>&p=<pass>&sec=<WPA2|WPA3|OPEN>&ip=..&port=..&fp=..&n=..`
 */
data class QrPayload(
    val ssid: String,
    val passphrase: String,
    val security: Security,
    val ip: String,
    val port: Int,
    val fingerprint: String,
    val deviceName: String,
    val protocolVersion: Int = Protocol.VERSION,
) {
    enum class Security { OPEN, WPA2, WPA3 }

    fun encode(): String = buildString {
        append(PREFIX)
        append("v=").append(protocolVersion)
        append("&s=").append(enc(ssid))
        append("&p=").append(enc(passphrase))
        append("&sec=").append(security.name)
        append("&ip=").append(enc(ip))
        append("&port=").append(port)
        append("&fp=").append(fingerprint.lowercase())
        append("&n=").append(enc(deviceName))
    }

    companion object {
        const val PREFIX = "droplink://join?"
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val FP = Regex("^[0-9a-fA-F]{64}$")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

        /** Returns null if [text] is not a valid DropLink QR code. */
        fun parse(text: String?): QrPayload? {
            if (text == null || !text.startsWith(PREFIX)) return null
            return runCatching {
                val params = text.removePrefix(PREFIX).split('&').associate { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) throw IllegalArgumentException("bad param")
                    part.substring(0, eq) to dec(part.substring(eq + 1))
                }
                val version = params.getValue("v").toInt()
                val ip = params.getValue("ip")
                val m = IPV4.matchEntire(ip) ?: throw IllegalArgumentException("bad ip")
                if (m.groupValues.drop(1).any { it.toInt() > 255 }) throw IllegalArgumentException("bad ip")
                val port = params.getValue("port").toInt()
                require(port in 1..65535)
                val fp = params.getValue("fp")
                require(FP.matches(fp))
                val ssid = params.getValue("s")
                require(ssid.isNotEmpty() && ssid.length <= 64)
                val security = runCatching { Security.valueOf(params["sec"] ?: "WPA2") }.getOrDefault(Security.WPA2)
                val pass = params["p"] ?: ""
                if (security != Security.OPEN) require(pass.length in 8..63)
                QrPayload(
                    ssid = ssid,
                    passphrase = pass,
                    security = security,
                    ip = ip,
                    port = port,
                    fingerprint = fp.lowercase(),
                    deviceName = (params["n"] ?: "").take(64),
                    protocolVersion = version,
                )
            }.getOrNull()
        }
    }
}
