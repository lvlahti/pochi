package io.github.lvlahti.pochi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Bind ONLY to the transport the user explicitly selected; never bind to 0.0.0.0. */
object LocalNetwork {
    fun findAddress(context: Context, transport: String): Inet4Address? {
        if (transport == StreamSettings.TRANSPORT_USB) return UsbNetwork.findAddress()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
            val match = addresses.map { it.address }.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            if (match != null) return match
        }
        // Some OEM ROMs hide Wi-Fi link properties; use wlan interfaces, never the VPN.
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        return Collections.list(interfaces).asSequence()
            .filter { it.name.startsWith("wlan") && runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }
}