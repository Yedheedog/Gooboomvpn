package com.example.vpngate.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnPhase { IDLE, STARTING, CONNECTED, DISCONNECTING, ERROR }

data class VpnUiState(
    val phase: VpnPhase = VpnPhase.IDLE,
    val serverLabel: String? = null,
    val message: String = "Choose a VPN Gate relay to connect"
)

/** In-process state channel shared by the foreground service and Compose UI. */
object VpnConnectionState {
    private val mutable = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = mutable.asStateFlow()

    internal fun update(value: VpnUiState) { mutable.value = value }
}