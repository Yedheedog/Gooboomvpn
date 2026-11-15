package com.example.vpngate.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.concurrent.TimeUnit

enum class VpnSourceKind { VPN_GATE_CSV, OPENVPN_PROFILE }

data class VpnSource(
    val id: String,
    val name: String,
    val kind: VpnSourceKind,
    val location: String,
    val enabled: Boolean = true,
    val profileCiphertextBase64: String = ""
)

class VpnGateRepository(context: Context? = null) {
    private val appContext = context?.applicationContext
    private val preferences = appContext?.getSharedPreferences("vpn_sources_v1", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        // Source URLs are required to be HTTPS. Do not silently follow a redirect to
        // cleartext HTTP or an unreviewed alternate endpoint.
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun sources(): List<VpnSource> = runCatching {
        val json = preferences?.getString("sources", null) ?: return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                VpnSource(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    kind = VpnSourceKind.valueOf(item.getString("kind")),
                    location = item.getString("location"),
                    enabled = item.optBoolean("enabled", true),
                    profileCiphertextBase64 = item.optString("profileEncrypted", "")
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun saveSources(values: List<VpnSource>) {
        val array = JSONArray()
        values.forEach { source ->
            array.put(JSONObject().apply {
                put("id", source.id); put("name", source.name); put("kind", source.kind.name)
                put("location", source.location); put("enabled", source.enabled)
                if (source.profileCiphertextBase64.isNotEmpty()) put("profileEncrypted", source.profileCiphertextBase64)
            })
        }
        preferences?.edit()?.putString("sources", array.toString())?.apply()
    }

    fun addUrlSource(name: String, url: String): VpnSource {
        require(preferences != null) { "Persistent source management requires Android storage context" }
        val parsed = URI(url.trim())
        require(parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank()) {
            "Server-list sources must use a valid HTTPS URL"
        }
        require(url.length <= 2048) { "Source URL is too long" }
        val source = VpnSource(UUID.randomUUID().toString(), name.trim().ifBlank { parsed.host }, VpnSourceKind.VPN_GATE_CSV, parsed.toASCIIString())
        require(sources().none { it.location == source.location }) { "This source URL is already added" }
        saveSources(sources() + source)
        return source
    }

    fun addProfileSource(name: String, profile: ByteArray): VpnSource {
        require(preferences != null) { "Persistent source management requires Android storage context" }
        require(profile.isNotEmpty() && profile.size <= 2 * 1024 * 1024) { "Profile must be between 1 byte and 2 MB" }
        val text = profile.toString(Charsets.UTF_8)
        require(Regex("(?m)^\\s*(client|remote\\s+)").containsMatchIn(text)) { "File does not look like an OpenVPN client profile" }
        val source = VpnSource(
            UUID.randomUUID().toString(), name.trim().ifBlank { "Imported profile" },
            VpnSourceKind.OPENVPN_PROFILE, "imported://${UUID.randomUUID()}",
            profileCiphertextBase64 = encryptProfile(profile)
        )
        saveSources(sources() + source)
        return source
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        saveSources(sources().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun removeSource(id: String) {
        saveSources(sources().filterNot { it.id == id })
    }

    suspend fun fetchServers(): List<VpnGateServer> = withContext(Dispatchers.IO) {
        val configured = sources().filter { it.enabled }
        val builtIn = VpnSource("vpngate-default", "VPN Gate", VpnSourceKind.VPN_GATE_CSV, "https://www.vpngate.net/api/iphone/")
        val allSources = listOf(builtIn) + configured
        deduplicateServers(allSources.flatMap { source ->
            runCatching {
                when (source.kind) {
                    VpnSourceKind.OPENVPN_PROFILE -> listOf(profileServer(source))
                    VpnSourceKind.VPN_GATE_CSV -> fetchCsv(source)
                }
            }.getOrDefault(emptyList())
        })
    }

    /** Keep the first provider's metadata while removing the same relay repeated by other sources. */
    internal fun deduplicateServers(servers: List<VpnGateServer>): List<VpnGateServer> =
        servers.distinctBy { "${it.ip.trim().lowercase()}:${it.hostName.trim().lowercase()}" }

    private fun fetchCsv(source: VpnSource): List<VpnGateServer> {
        val request = Request.Builder().url(source.location)
            .header("User-Agent", "VPNGateConnect/0.1 Android").build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Source ${source.name} returned HTTP ${response.code}" }
            parseCsv(response.body?.string().orEmpty(), source)
        }
    }

    private fun profileServer(source: VpnSource): VpnGateServer {
        val decoded = decryptProfile(source.profileCiphertextBase64)
        return try {
            val text = decoded.toString(Charsets.UTF_8)
            val remote = Regex("(?m)^\\s*remote\\s+(\\S+)\\s+(\\d+)").find(text)
            VpnGateServer(
                hostName = source.name, ip = remote?.groupValues?.get(1) ?: "Imported profile",
                score = 0, pingMs = Long.MAX_VALUE, speedBps = 1,
                country = "Imported", countryCode = "--", sessions = 0, uptime = 0,
                openVpnConfigBase64 = Base64.encodeToString(decoded, Base64.NO_WRAP), sourceId = source.id,
                sourceName = source.name, isImportedProfile = true
            )
        } finally {
            decoded.fill(0)
        }
    }
    internal fun parseCsv(csv: String): List<VpnGateServer> = parseCsv(
        csv,
        VpnSource("vpngate-default", "VPN Gate", VpnSourceKind.VPN_GATE_CSV, "https://www.vpngate.net/api/iphone/")
    )

    internal fun parseCsv(csv: String, source: VpnSource): List<VpnGateServer> {
        val lines = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("*") }.toList()
        if (lines.size < 2) return emptyList()
        val rows = lines.drop(1).mapNotNull(::parseCsvLine)
        return rows.mapNotNull { c ->
            if (c.size < 15 || c[14].isBlank()) return@mapNotNull null
            runCatching {
                VpnGateServer(
                    hostName = c[0], ip = c[1], score = c[2].toLongOrNull() ?: 0,
                    pingMs = c[3].toLongOrNull() ?: Long.MAX_VALUE,
                    speedBps = c[4].toLongOrNull() ?: 0,
                    country = c[5], countryCode = c[6],
                    sessions = c[7].toIntOrNull() ?: 0,
                    uptime = c[8].toLongOrNull() ?: 0,
                    openVpnConfigBase64 = c[14], sourceId = source.id, sourceName = source.name
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


    private companion object {
        private const val PROFILE_KEY_ALIAS = "vpn_gate_imported_profile_key"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }

    /** Encrypt imported credentials at rest with a non-exportable Android Keystore key. */
    private fun encryptProfile(plainText: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, profileKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText)
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptProfile(encoded: String): ByteArray {
        val payload = Base64.decode(encoded, Base64.DEFAULT)
        require(payload.size > GCM_IV_BYTES) { "Encrypted OpenVPN profile is incomplete" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, profileKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } finally {
            payload.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun profileKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(PROFILE_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                PROFILE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
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
