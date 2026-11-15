package com.example.vpngate.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.vpngate.data.VpnGateServer

/**
 * Starts/stops the Android service that owns the VPN lifecycle.
 * A successful return means only that the service request was accepted; CONNECTED is
 * published exclusively by the native OpenVPN client after its connection event.
 */
class NativeOpenVpn3Engine(private val context: Context) {
    fun connect(server: VpnGateServer, inlineProfile: ByteArray) {
        check(VpnService.prepare(context) == null) {
            "Android VPN permission has not been granted. Request consent before starting the service."
        }
        val intent = Intent(context, VpnGateVpnService::class.java).apply {
            action = VpnGateVpnService.ACTION_CONNECT
            putExtra(VpnGateVpnService.EXTRA_SERVER, "${server.country} (${server.ip})")
            putExtra(VpnGateVpnService.EXTRA_SERVER_IP, server.ip)
            putExtra(VpnGateVpnService.EXTRA_PROFILE, inlineProfile.copyOf())
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        context.startService(Intent(context, VpnGateVpnService::class.java).apply {
            action = VpnGateVpnService.ACTION_DISCONNECT
        })
    }
}