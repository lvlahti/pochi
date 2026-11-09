package io.github.lvlahti.pochi

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Never fall back to wlan0: a capture stream must not accidentally appear on Wi-Fi. */
object UsbNetwork {
    private val usbNames = listOf("rndis", "usb", "ncm", "tether")

    fun findAddress(): Inet4Address? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        return Collections.list(interfaces)
            .asSequence()
            .filter { network ->
                runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) &&
                    usbNames.any { prefix -> network.name.lowercase().startsWith(prefix) }
            }
            .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
    }
}