package com.example.vpngate.data

object ServerRanker {
    /** Prefer low measured ping, high throughput, high uptime and fewer active sessions. */
    fun fastest(servers: List<VpnGateServer>): VpnGateServer? = servers
        .asSequence()
        .filter { it.openVpnConfigBase64.isNotBlank() && it.speedBps > 0 && it.pingMs < Long.MAX_VALUE }
        .sortedWith(
            compareBy<VpnGateServer> { it.pingMs }
                .thenByDescending { it.speedBps }
                .thenByDescending { it.uptime }
                .thenBy { it.sessions }
        ).firstOrNull()
}