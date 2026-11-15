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
        val candidate = message.replace("\r", "").trim().take(MAX_ENTRY_CHARS)
        if (candidate.isEmpty()) return

        val lowered = candidate.lowercase()
        val sensitive = listOf(
            "password", "username", "auth-user-pass", "auth-token",
            "auth_token", "private key", "-----begin"
        ).any { lowered.contains(it) }
        val safeMessage = if (sensitive) {
            "[redacted sensitive diagnostic record]"
        } else {
            candidate
        }

        val timestamped = java.util.Date().toString() + "  " + safeMessage
        val current = mutableText.value
        val lines = if (current.isEmpty()) emptyList() else current.split("\n")
        mutableText.value = (lines + timestamped).takeLast(MAX_ENTRIES).joinToString("\n")
    }
}
