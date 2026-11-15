package com.example.vpngate

import android.os.Bundle
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
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

private fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%02d:%02d".format(minutes, remainder)
}

private fun formatTraffic(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024 * 1024 -> String.format("%.2f GB", value / (1024.0 * 1024 * 1024))
        value >= 1024.0 * 1024 -> String.format("%.1f MB", value / (1024.0 * 1024))
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VpnGateApp(activity: ComponentActivity) {
    val repository = remember { VpnGateRepository(activity.applicationContext) }
    val engine = remember { NativeOpenVpn3Engine(activity.applicationContext) }
    val vpnState by VpnConnectionState.state.collectAsState()
    val nativeLog by com.example.vpngate.vpn.NativeDiagnosticLog.text.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showLogDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
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
        ServerRanker.sort(servers, sortMode, if (countryFilter == "ALL") emptySet() else setOf(countryFilter))
    }

    fun refresh() {
        scope.launch {
            loading = true; message = "Fetching VPN Gate relay list…"
            runCatching { repository.fetchServers() }
                .onSuccess { list ->
                    val previouslySelectedIp = selected?.ip
                    servers = list
                    selected = list.firstOrNull { it.ip == previouslySelectedIp }
                        ?: ServerRanker.fastest(list)
                    val sourceWarnings = repository.lastFetchWarnings
                    message = when {
                        list.isEmpty() && sourceWarnings.isNotEmpty() ->
                            "No relays loaded. Source errors: ${sourceWarnings.joinToString("; ")}"
                        list.isEmpty() -> "No OpenVPN relays found. Try again later."
                        sourceWarnings.isNotEmpty() ->
                            "${list.size} relays available; ${sourceWarnings.size} source(s) failed: ${sourceWarnings.joinToString("; ")}"
                        else -> ""
                    }
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
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BUST THE GATE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("ADS-FREE OPENVPN CONNECTOR", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                }
                IconButton(onClick = { sourceItems = repository.sources(); showSourceDialog = true }, enabled = true) { Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        vpnState.phase.name.replace("_", " "),
                        color = if (vpnState.phase == VpnPhase.CONNECTED) MaterialTheme.colorScheme.primary else Color.LightGray,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vpnState.serverLabel ?: selected?.country ?: "Automatic server", color = if (vpnState.phase == VpnPhase.CONNECTED) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(selected?.let { "${it.pingMs} ms  •  ${it.displaySpeed}" } ?: "Fastest available relay", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                            if (vpnState.phase == VpnPhase.CONNECTED) {
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    Text("↓ Downloaded  ${formatTraffic(vpnState.downloadedBytes)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    Text("↑ Uploaded  ${formatTraffic(vpnState.uploadedBytes)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    Text("◷ ${formatDuration(vpnState.sessionDurationSeconds)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(vpnState.message, color = if (vpnState.phase == VpnPhase.ERROR) Color(0xFFFF8A80) else Color(0xFFFFC46B), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
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
                    }, modifier = Modifier.fillMaxWidth().height(44.dp), enabled = !loading && (selected != null || vpnState.phase == VpnPhase.CONNECTED || vpnState.phase == VpnPhase.STARTING)) {
                        Text(if (vpnState.phase == VpnPhase.CONNECTED || vpnState.phase == VpnPhase.STARTING) "DISCONNECT" else "CONNECT TO SELECTED")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            var sortMenuExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AVAILABLE SERVERS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(
                            when (sortMode) {
                                RelaySelectionMode.LOWEST_PING -> "Lowest ping"
                                RelaySelectionMode.HIGHEST_SPEED -> "Highest speed"
                                RelaySelectionMode.BALANCED -> "Balanced"
                            }
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose server sorting")
                    }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Lowest ping") },
                            onClick = { sortMode = RelaySelectionMode.LOWEST_PING; sortMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Highest speed") },
                            onClick = { sortMode = RelaySelectionMode.HIGHEST_SPEED; sortMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Balanced") },
                            onClick = { sortMode = RelaySelectionMode.BALANCED; sortMenuExpanded = false }
                        )
                    }
                }
            }
            Text("${servers.size} OpenVPN relays available", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            if (message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(message, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (availableCountries.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Country", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { showCountryPicker = true }) {
                        Text(if (countryFilter == "ALL") "All countries" else countryFilter)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose country")
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
            Spacer(Modifier.height(6.dp))
            Text(
                "VPN Gate Connect — Build #${BuildConfig.BUILD_NUMBER}",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showLogDialog = true }
                )
            )
        }
    }
    if (showCountryPicker) {
        AlertDialog(
            onDismissRequest = { showCountryPicker = false },
            title = { Text("Filter by country") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = { countryFilter = "ALL"; showCountryPicker = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("All countries", modifier = Modifier.weight(1f))
                        if (countryFilter == "ALL") Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                    availableCountries.forEach { code ->
                        TextButton(
                            onClick = { countryFilter = code; showCountryPicker = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(code, modifier = Modifier.weight(1f))
                            if (countryFilter == code) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryPicker = false }) { Text("CLOSE") }
            }
        )
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