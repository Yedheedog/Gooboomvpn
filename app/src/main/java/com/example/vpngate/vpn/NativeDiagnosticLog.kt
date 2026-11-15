package com.example.vpngate.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small in-memory ring buffer for redacted OpenVPN diagnostics.
 * Logs are not persisted and are cleared when the app process exits.
 */
object NativeDiagnosticLog {
    private const val MAX_ENTRIES = 250
    private const val MAX_ENTRY_CHARS = 1800

    private val mutableText = MutableStateFlow("")
    val text: StateFlow<String> = mutableText.asStateFlow()

    @Synchronized
    fun append(message: String) {
        val safeMessage = message
            .replace("\r", "")
            .trim()
            .take(MAX_ENTRY_CHARS)
        if (safeMessage.isEmpty()) return

        val timestamped = java.util.Date().toString() + "  " + safeMessage
        val current = mutableText.value
        val lines = if (current.isEmpty()) emptyList() else current.split("\n")
        mutableText.value = (lines + timestamped).takeLast(MAX_ENTRIES).joinToString("\n")
    }
}
