package com.example.vpngate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDiscoveryTest {
    @Test
    fun parsesQuotedCsvFieldsAndIgnoresCommentLines() {
        val csv = """
            *vpn_servers
            HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            jp.example,192.0.2.10,10,12,1000000,"Japan, East",JP,3,900,4,5,0,operator,"hello, ""world""",cHJvZmlsZQ==
            malformed,row
        """.trimIndent()

        val servers = VpnGateRepository().parseCsv(csv)

        assertEquals(1, servers.size)
        assertEquals("Japan, East", servers.single().country)
        assertEquals("cHJvZmlsZQ==", servers.single().openVpnConfigBase64)
    }

    @Test
    fun fastestUsesPingThenThroughputUptimeAndSessions() {
        val slower = server(host = "slower", ping = 20, speed = 9_000_000)
        val lowerThroughput = server(host = "low-throughput", ping = 10, speed = 1_000_000)
        val faster = server(host = "fastest", ping = 10, speed = 2_000_000)
        val selected = ServerRanker.fastest(listOf(slower, lowerThroughput, faster))

        assertEquals("fastest", selected?.hostName)
    }

    @Test
    fun fastestRejectsInvalidRelayMetricsAndMissingProfiles() {
        val invalid = listOf(
            server(host = "negative-ping", ping = -1),
            server(host = "unknown-ping", ping = Long.MAX_VALUE),
            server(host = "zero-speed", speed = 0),
            server(host = "negative-uptime", uptime = -1),
            server(host = "negative-sessions", sessions = -1),
            server(host = "no-profile", profile = "")
        )

        assertNull(ServerRanker.fastest(invalid))
    }

    private fun server(
        host: String,
        ping: Long = 10,
        speed: Long = 1_000_000,
        uptime: Long = 100,
        sessions: Int = 2,
        profile: String = "cHJvZmlsZQ=="
    ) = VpnGateServer(
        hostName = host,
        ip = "192.0.2.1",
        score = 0,
        pingMs = ping,
        speedBps = speed,
        country = "Japan",
        countryCode = "JP",
        sessions = sessions,
        uptime = uptime,
        openVpnConfigBase64 = profile
    )
}
