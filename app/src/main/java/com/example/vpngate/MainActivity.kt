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
    val repository = remember { VpnGateRepository(activity.applicationContext) }
    val engine = remember { NativeOpenVpn3Engine(activity.applicationContext) }
    val vpnState by VpnConnectionState.state.collectAsState()
    val nativeLog by com.example.vpngate.vpn.NativeDiagnosticLog.text.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showLogDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var sourceNameInput by remember { mutableStateOf("") }
    var sourceUrlInput by remember { mutableStateOf("") }
    var sourceMessage by remember { mutableStateOf("") }
    var sourceItems by remember { mutableStateOf(repository.sources()) }
    var pendingConnect by remember { mutableStateOf<Triple<VpnGateServer, ByteArray, String?>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingConnect
        pendingConnect = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            runCatching { engine.connect(pending.first, pending.second, pending.third) }
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
    var sortMode by remember { mutableStateOf(RelaySelectionMode.LOWEST_PING) }
    var countryFilter by remember { mutableStateOf("ALL") }
    val availableCountries = remember(servers) { servers.map { it.countryCode }.filter { it.isNotBlank() && it != "--" }.distinct().sorted() }
    val visibleServers = remember(servers, sortMode, countryFilter) {
        // LazyColumn virtualizes rows, so retain imported profiles even when a source
        // returns more than 100 public relays.
        ServerRanker.sort(servers, sortMode, if (countryFilter == "ALL") emptySet() else setOf(countryFilter))
    }

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

    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read selected profile")
            try {
                repository.addProfileSource(sourceNameInput.ifBlank { "Imported profile" }, bytes)
                sourceItems = repository.sources()
                sourceMessage = "OpenVPN profile imported"
                refresh()
            } finally {
                bytes.fill(0)
            }
        }.onFailure { sourceMessage = it.message ?: "Could not import profile" }
    }

    LaunchedEffect(countryFilter, servers) {
        if (servers.isNotEmpty()) {
            val allowed = if (countryFilter == "ALL") emptySet() else setOf(countryFilter)
            if (selected == null || (allowed.isNotEmpty() && selected?.countryCode !in allowed)) {
                selected = ServerRanker.sort(servers, RelaySelectionMode.LOWEST_PING, allowed).firstOrNull()
            }
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
                IconButton(onClick = { sourceItems = repository.sources(); showSourceDialog = true }, enabled = true) { Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
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
                                            pendingConnect = Triple(server, profileBytes, countryFilter.takeIf { it != "ALL" })
                                            permissionLauncher.launch(consentIntent)
                                        } else {
                                            runCatching { engine.connect(server, profileBytes, countryFilter.takeIf { it != "ALL" }) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = sortMode == RelaySelectionMode.LOWEST_PING, onClick = { sortMode = RelaySelectionMode.LOWEST_PING }, label = { Text("Lowest ping") })
                FilterChip(selected = sortMode == RelaySelectionMode.HIGHEST_SPEED, onClick = { sortMode = RelaySelectionMode.HIGHEST_SPEED }, label = { Text("Highest speed") })
                FilterChip(selected = sortMode == RelaySelectionMode.BALANCED, onClick = { sortMode = RelaySelectionMode.BALANCED }, label = { Text("Balanced") })
            }
            if (availableCountries.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Country", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    Box {
                        TextButton(onClick = { expanded = true }) { Text(if (countryFilter == "ALL") "All countries" else countryFilter) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("All countries") }, onClick = { countryFilter = "ALL"; expanded = false })
                            availableCountries.forEach { code -> DropdownMenuItem(text = { Text(code) }, onClick = { countryFilter = code; expanded = false }) }
                        }
                    }
                    Text("${visibleServers.size} shown", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(visibleServers) { server ->
                    Card(onClick = { selected = server }, colors = CardDefaults.cardColors(containerColor = if (selected == server) Color(0xFF1C3A3A) else MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${server.country} (${server.countryCode})", fontWeight = FontWeight.SemiBold)
                                Text(server.sourceName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
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
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("VPN sources") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a compatible HTTPS VPN Gate CSV server-list URL or import an .ovpn profile.")
                    OutlinedTextField(value = sourceNameInput, onValueChange = { sourceNameInput = it }, label = { Text("Source name (optional)") }, singleLine = true)
                    OutlinedTextField(value = sourceUrlInput, onValueChange = { sourceUrlInput = it }, label = { Text("HTTPS server-list URL") }, singleLine = true)
                    Button(onClick = {
                        runCatching { repository.addUrlSource(sourceNameInput, sourceUrlInput) }
                            .onSuccess { sourceItems = repository.sources(); sourceUrlInput = ""; sourceNameInput = ""; sourceMessage = "Source added"; refresh() }
                            .onFailure { sourceMessage = it.message ?: "Could not add source" }
                    }, enabled = sourceUrlInput.isNotBlank()) { Text("ADD URL SOURCE") }
                    OutlinedButton(onClick = { profilePicker.launch(arrayOf("application/x-openvpn-profile", "application/octet-stream", "text/plain")) }) { Text("IMPORT .OVPN PROFILE") }
                    if (sourceMessage.isNotBlank()) Text(sourceMessage, color = MaterialTheme.colorScheme.primary)
                    Divider()
                    Text("Configured sources", fontWeight = FontWeight.Bold)
                    if (sourceItems.isEmpty()) Text("No additional sources yet.")
                    sourceItems.forEach { source ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, fontWeight = FontWeight.SemiBold)
                                Text(source.kind.name.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Switch(checked = source.enabled, onCheckedChange = { enabled -> repository.setSourceEnabled(source.id, enabled); sourceItems = repository.sources(); refresh() })
                            TextButton(onClick = { repository.removeSource(source.id); sourceItems = repository.sources(); refresh() }) { Text("REMOVE") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSourceDialog = false }) { Text("DONE") } }
        )
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