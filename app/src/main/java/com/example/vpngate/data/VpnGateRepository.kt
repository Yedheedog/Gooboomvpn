package com.example.vpngate.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class VpnGateRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchServers(): List<VpnGateServer> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.vpngate.net/api/iphone/")
            .header("User-Agent", "VPNGateConnect/0.1 Android")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "VPN Gate returned HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            parseCsv(body)
        }
    }

    internal fun parseCsv(csv: String): List<VpnGateServer> {
        val lines = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("*") }.toList()
        if (lines.size < 2) return emptyList()
        val rows = lines.drop(1).mapNotNull(::parseCsvLine)
        return rows.mapNotNull { c ->
            // VPN Gate's CSV columns: HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,
            // NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            if (c.size < 15 || c[14].isBlank()) return@mapNotNull null
            runCatching {
                VpnGateServer(
                    hostName = c[0], ip = c[1], score = c[2].toLongOrNull() ?: 0,
                    pingMs = c[3].toLongOrNull() ?: Long.MAX_VALUE,
                    speedBps = c[4].toLongOrNull() ?: 0,
                    country = c[5], countryCode = c[6],
                    sessions = c[7].toIntOrNull() ?: 0,
                    uptime = c[8].toLongOrNull() ?: 0,
                    openVpnConfigBase64 = c[14]
                )
            }.getOrNull()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += field.toString(); field.setLength(0) }
                else -> field.append(ch)
            }
            i++
        }
        out += field.toString()
        return out
    }

    fun decodeProfile(server: VpnGateServer): ByteArray {
        val bytes = Base64.decode(server.openVpnConfigBase64, Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "VPN Gate returned an empty OpenVPN profile" }
        val prefix = bytes.toString(Charsets.UTF_8).take(4096)
        require(prefix.contains("client") || prefix.contains("remote ")) {
            "Downloaded profile does not look like an OpenVPN client configuration"
        }
        return bytes
    }
}