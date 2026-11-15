package com.example.vpngate

import android.os.Bundle
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vpngate.data.*
import com.example.vpngate.vpn.NativeOpenVpn3Engine
import com.example.vpngate.vpn.VpnConnectionState
import com.example.vpngate.vpn.VpnPhase
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF63E6BE), background = Color(0xFF0B1220), surface = Color(0xFF151F30))) { VpnGateApp(this) } }
    }
}

@Composable
private fun VpnGateApp(activity: ComponentActivity) {
    val repository = remember { VpnGateRepository() }
    val engine = remember { NativeOpenVpn3Engine(activity.applicationContext) }
    val vpnState by VpnConnectionState.state.collectAsState()
    val nativeLog by com.example.vpngate.vpn.NativeDiagnosticLog.text.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showLogDialog by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<Pair<VpnGateServer, ByteArray>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingConnect
        pendingConnect = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            runCatching { engine.connect(pending.first, pending.second) }
                .onFailure { VpnConnectionState.update(com.example.vpngate.vpn.VpnUiState(VpnPhase.ERROR, pending.first.country, it.message ?: "Could not start VPN service")) }
            pending.second.fill(0)
        } else {
            pending?.second?.fill(0)
            VpnConnectionState.update(com.example.vpngate.vpn.VpnUiState(VpnPhase.IDLE, message = "VPN permission was not granted"))
        }
    }
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<VpnGateServer>>(emptyList()) }
    var selected by remember { mutableStateOf<VpnGateServer?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Load public relays to get started") }

    fun refresh() {
        scope.launch {
            loading = true; message = "Fetching VPN Gate relay list…"
            runCatching { repository.fetchServers() }
                .onSuccess { list ->
                    servers = list
                    selected = ServerRanker.fastest(list)
                    message = if (list.isEmpty()) "No OpenVPN relays found. Try again later." else "${list.size} OpenVPN relays available"
                }
                .onFailure { message = "Could not load relays: ${it.message}" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("VPN GATE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("OPENVPN RELAY CLIENT", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                }
                IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            Spacer(Modifier.height(28.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vpnState.phase.name.replace("_", " "), color = if (vpnState.phase == VpnPhase.CONNECTED) MaterialTheme.colorScheme.primary else Color.LightGray, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.size(124.dp).background(Color(0xFF20364A), RoundedCornerShape(100)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(66.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(vpnState.serverLabel ?: selected?.country ?: "Automatic server", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(selected?.let { "${it.pingMs} ms  •  ${it.displaySpeed}" } ?: "Fastest available relay", color = Color.LightGray)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = {
                        if (vpnState.phase == VpnPhase.CONNECTED || vpnState.phase == VpnPhase.STARTING) {
                            engine.disconnect()
                        } else {
                            val server = selected
                            if (server == null) refresh() else scope.launch {
                                runCatching { repository.decodeProfile(server) }
                                    .onSuccess { profileBytes ->
                                        val consentIntent = VpnService.prepare(activity)
                                        if (consentIntent != null) {
                                            pendingConnect = server to profileBytes
                                            permissionLauncher.launch(consentIntent)
                                        } else {
                                            runCatching { engine.connect(server, profileBytes) }
                                                .onFailure { VpnConnectionState.update(com.example.vpngate.vpn.VpnUiState(VpnPhase.ERROR, server.country, it.message ?: "Could not start VPN service")) }
                                            profileBytes.fill(0)
                                        }
                                    }
                                    .onFailure { VpnConnectionState.update(com.example.vpngate.vpn.VpnUiState(VpnPhase.ERROR, server.country, it.message ?: "Invalid VPN profile")) }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !loading && (selected != null || vpnState.phase == VpnPhase.CONNECTED || vpnState.phase == VpnPhase.STARTING)) {
                        Text(if (vpnState.phase == VpnPhase.CONNECTED || vpnState.phase == VpnPhase.STARTING) "DISCONNECT" else "CONNECT TO SELECTED")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(vpnState.message, color = if (vpnState.phase == VpnPhase.ERROR) Color(0xFFFF8A80) else Color(0xFFFFC46B), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AVAILABLE SERVERS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${servers.size} found", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(servers.take(100)) { server ->
                    Card(onClick = { selected = server }, colors = CardDefaults.cardColors(containerColor = if (selected == server) Color(0xFF1C3A3A) else MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${server.country} (${server.countryCode})", fontWeight = FontWeight.SemiBold)
                                Text("${server.ip}  •  ${server.sessions} sessions", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (server.pingMs == Long.MAX_VALUE) "—" else "${server.pingMs} ms", color = MaterialTheme.colorScheme.primary)
                                Text(server.displaySpeed, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (servers.isEmpty() && !loading) OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("LOAD SERVERS") }
            Text("VPN Gate relays are volunteer-operated. Avoid sensitive traffic on public relays.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showLogDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("VIEW / COPY DIAGNOSTIC LOG")
            }
            Spacer(Modifier.height(8.dp))
            Text("VPN Gate Connect — Build #${BuildConfig.BUILD_NUMBER}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("OpenVPN diagnostic log") },
            text = {
                SelectionContainer {
                    Text(
                        nativeLog.ifBlank { "No native OpenVPN logs captured yet. Start a connection attempt, then reopen this screen." },
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboardManager.setText(AnnotatedString(nativeLog)) }) {
                    Text("COPY LOG")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("CLOSE") }
            }
        )
    }

}