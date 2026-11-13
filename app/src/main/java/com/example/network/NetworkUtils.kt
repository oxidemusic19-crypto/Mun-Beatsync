package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getLocalHostIpAddress(context: Context): String {
        val candidates = mutableListOf<Pair<Int, String>>()

        // 1. Check active network LinkProperties first
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork
            val props: LinkProperties? = active?.let { cm.getLinkProperties(it) }
            props?.linkAddresses?.forEach { linkAddr ->
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: return@forEach
                    candidates.add(10 to host)
                }
            }
        }

        // 2. Scan all NetworkInterfaces (crucial when phone is acting as Wi-Fi Hotspot without upstream internet)
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp")) continue

                val priority = when {
                    name.startsWith("ap") || name.contains("softap") || name.contains("swlan") -> 1
                    name.startsWith("wlan") -> 2
                    name.startsWith("eth") -> 3
                    else -> 5
                }

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        addr.hostAddress?.let { ip ->
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        }

        return candidates.minByOrNull { it.first }?.second ?: "127.0.0.1"
    }

    fun getBroadcastAndGatewayTargets(context: Context): List<InetAddress> {
        val targets = linkedSetOf<String>()
        targets.add("255.255.255.255")

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.contains("rmnet") || name.contains("ccmni")) continue

                iface.interfaceAddresses.forEach { ifAddr ->
                    val addr = ifAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ifAddr.broadcast?.hostAddress?.let { targets.add(it) }
                        val ip = addr.hostAddress ?: ""
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
                            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
                        }
                    }
                }
            }
        }

        // Common Android hotspot gateway fallback
        val localIp = getLocalHostIpAddress(context)
        val parts = localIp.split(".")
        if (parts.size == 4) {
            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
        }

        return targets.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
    }
}
