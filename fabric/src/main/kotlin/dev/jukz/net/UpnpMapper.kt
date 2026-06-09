package dev.jukz.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL

/**
 * Real, dependency-free UPnP IGD client (the first rung of the NAT-traversal ladder). Discovers an
 * Internet Gateway Device via SSDP, then drives its WAN connection service over SOAP to (a) read the
 * router's public IP and (b) map a TCP port — turning a NAT'd host into a directly-reachable endpoint
 * with no hole punching. Many home routers support this out of the box.
 *
 * FLAGGED: it issues real SSDP/SOAP to whatever gateway is on the LAN, so it requires a real router
 * and live-network testing. On any failure (no IGD, UPnP disabled, SOAP error) every method returns
 * null and the caller falls back (STUN public IP + manual forward, or ICE/relay later).
 */
class UpnpMapper(
    private val discoverTimeoutMs: Int = 3_000,
    private val soapTimeoutMs: Int = 4_000,
) {

    data class Mapping(val externalIp: String, val externalPort: Int)

    /** Map [internalPort] (TCP) and return the resulting public {ip, port}, or null if unavailable. */
    fun mapPort(internalPort: Int): Mapping? {
        val service = discoverService() ?: return null
        val localIp = localAddressTowards(service.controlUrl) ?: return null
        val mapped = addPortMapping(service, internalPort, localIp) ?: return null
        if (!mapped) return null
        val externalIp = externalIp(service) ?: return null
        return Mapping(externalIp, internalPort)
    }

    private data class Service(val serviceType: String, val controlUrl: URL)

    /** SSDP M-SEARCH for an IGD; fetch its description and find the WAN connection control URL. */
    private fun discoverService(): Service? {
        val location = ssdpSearch() ?: return null
        val xml = httpGet(location) ?: return null
        // Find a WAN(IP|PPP)Connection service block and its controlURL.
        val serviceType = SERVICE_TYPES.firstOrNull { xml.contains(it) } ?: return null
        val block = xml.substringAfter(serviceType, "").ifEmpty { return null }
        val control = CONTROL_URL.find(block)?.groupValues?.get(1)?.trim() ?: return null
        val controlUrl = runCatching { URI(location).resolve(control).toURL() }.getOrNull() ?: return null
        return Service(serviceType, controlUrl)
    }

    private fun ssdpSearch(): String? {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
            ).toByteArray()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = discoverTimeoutMs
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val text = String(buf, 0, response.length)
                LOCATION.find(text)?.groupValues?.get(1)?.trim()
            }
        }.getOrNull()
    }

    private fun addPortMapping(service: Service, port: Int, localIp: String): Boolean? {
        val body =
            "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>$port</NewExternalPort>" +
                "<NewProtocol>TCP</NewProtocol>" +
                "<NewInternalPort>$port</NewInternalPort>" +
                "<NewInternalClient>$localIp</NewInternalClient>" +
                "<NewEnabled>1</NewEnabled>" +
                "<NewPortMappingDescription>jukz</NewPortMappingDescription>" +
                "<NewLeaseDuration>0</NewLeaseDuration>"
        return soap(service, "AddPortMapping", body)?.let { true }
    }

    private fun externalIp(service: Service): String? {
        val response = soap(service, "GetExternalIPAddress", "") ?: return null
        return EXTERNAL_IP.find(response)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    private fun soap(service: Service, action: String, innerBody: String): String? {
        val envelope =
            "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"${service.serviceType}\">$innerBody</u:$action></s:Body></s:Envelope>"
        return runCatching {
            val conn = (service.controlUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = soapTimeoutMs
                readTimeout = soapTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${service.serviceType}#$action\"")
            }
            conn.outputStream.use { it.write(envelope.toByteArray()) }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        }.getOrNull()
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = soapTimeoutMs
            readTimeout = soapTimeoutMs
        }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
    }.getOrNull()

    /** The local address this host uses to reach the gateway — the NewInternalClient for the mapping. */
    private fun localAddressTowards(controlUrl: URL): String? = runCatching {
        java.net.Socket().use { s ->
            s.connect(InetSocketAddress(controlUrl.host, if (controlUrl.port > 0) controlUrl.port else 80), soapTimeoutMs)
            s.localAddress.hostAddress
        }
    }.getOrNull()

    private companion object {
        val SERVICE_TYPES = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )
        val LOCATION = Regex("(?i)LOCATION:\\s*(\\S+)")
        val CONTROL_URL = Regex("(?i)<controlURL>\\s*(.*?)\\s*</controlURL>")
        val EXTERNAL_IP = Regex("(?i)<NewExternalIPAddress>\\s*(.*?)\\s*</NewExternalIPAddress>")
    }
}
