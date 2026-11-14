package com.example.vpngate.data

data class VpnGateServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Long,
    val speedBps: Long,
    val country: String,
    val countryCode: String,
    val sessions: Int,
    val uptime: Long,
    val openVpnConfigBase64: String
) {
    val displaySpeed: String get() = when {
        speedBps >= 1_000_000 -> "%.1f Mbps".format(speedBps / 1_000_000.0)
        speedBps >= 1_000 -> "%.0f Kbps".format(speedBps / 1_000.0)
        else -> "$speedBps bps"
    }
}