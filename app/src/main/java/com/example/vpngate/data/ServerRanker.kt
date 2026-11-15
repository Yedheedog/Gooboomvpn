package com.example.vpngate.data

enum class RelaySelectionMode {
    LOWEST_PING,
    HIGHEST_SPEED,
    BALANCED
}

/**
 * Shared relay ranking policy for auto-selection and the server-list filters.
 *
 * Published VPN Gate ping/speed values are hints, not measurements of the user's
 * actual route. Callers can supply measured latency and throughput after probing.
 */
object ServerRanker {
    fun filterByCountry(
        servers: List<VpnGateServer>,
        allowedCountryCodes: Set<String>
    ): List<VpnGateServer> {
        if (allowedCountryCodes.isEmpty()) return servers
        val allowed = allowedCountryCodes.map { it.trim().uppercase() }.toSet()
        return servers.filter { it.countryCode.trim().uppercase() in allowed }
    }

    fun sort(
        servers: List<VpnGateServer>,
        mode: RelaySelectionMode,
        allowedCountryCodes: Set<String> = emptySet(),
        measuredPingMs: Map<String, Long> = emptyMap(),
        measuredSpeedBps: Map<String, Long> = emptyMap()
    ): List<VpnGateServer> {
        val eligible = filterByCountry(servers, allowedCountryCodes).filter(::isEligible)
        fun ping(server: VpnGateServer): Long =
            measuredPingMs[server.ip]?.takeIf { it >= 0 } ?: server.pingMs
        fun speed(server: VpnGateServer): Long =
            measuredSpeedBps[server.ip]?.takeIf { it > 0 } ?: server.speedBps

        return when (mode) {
            RelaySelectionMode.LOWEST_PING -> eligible.sortedWith(
                compareBy<VpnGateServer> { ping(it) }
                    .thenByDescending { speed(it) }
                    .thenByDescending { it.uptime }
                    .thenBy { it.sessions }
            )
            RelaySelectionMode.HIGHEST_SPEED -> eligible.sortedWith(
                compareByDescending<VpnGateServer> { speed(it) }
                    .thenBy { ping(it) }
                    .thenByDescending { it.uptime }
                    .thenBy { it.sessions }
            )
            RelaySelectionMode.BALANCED -> eligible.sortedWith(
                compareBy<VpnGateServer> { balancedScore(ping(it), speed(it)) }
                    .thenBy { ping(it) }
                    .thenByDescending { speed(it) }
            )
        }
    }

    /** Default automatic policy deliberately favors latency, then throughput. */
    fun fastest(servers: List<VpnGateServer>): VpnGateServer? =
        sort(servers, RelaySelectionMode.LOWEST_PING).firstOrNull()

    fun isEligible(server: VpnGateServer): Boolean =
        server.openVpnConfigBase64.isNotBlank() &&
            server.speedBps > 0 &&
            server.pingMs >= 0 &&
            server.pingMs < Long.MAX_VALUE &&
            server.uptime >= 0 &&
            server.sessions >= 0

    private fun balancedScore(pingMs: Long, speedBps: Long): Double {
        // Normalize each metric to a practical range before combining them.
        // Lower score is better; neither a huge throughput nor tiny ping can
        // completely swamp the other metric.
        val latency = (pingMs.coerceAtLeast(1).toDouble() / 250.0).coerceAtMost(4.0)
        val throughput = (speedBps.coerceAtLeast(1).toDouble() / 50_000_000.0).coerceAtMost(4.0)
        return latency / 2.0 + 1.0 / (1.0 + throughput)
    }
}
