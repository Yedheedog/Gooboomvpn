package com.example.vpngate.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.Closeable
import java.net.InetAddress

/**
 * Android-side owner of the VpnService.Builder configuration for an OpenVPN TunBuilder adapter.
 *
 * Calls are intentionally explicit: the native adapter must translate each OpenVPN
 * TunBuilderBase callback to one of these methods. This class does not start OpenVPN by itself.
 * If an exclusion cannot be represented safely on the current Android version, it fails closed.
 */
@androidx.annotation.Keep
internal class AndroidTunBuilder(
    private val service: VpnService
) : Closeable {
    private val lock = Any()
    private var builder: VpnService.Builder? = service.Builder()
    private var established = false
    // Android implicitly enables a family when an address, route, or DNS server is added.
    // Track those changes so a later OpenVPN block-ipv4/block-ipv6 request cannot be ignored.
    private val configuredFamilies = mutableSetOf<Int>()

    fun setSessionName(name: String) = mutate {
        require(name.isNotBlank()) { "VPN session name must not be blank." }
        setSession(name)
    }

    fun setMtu(mtu: Int) = mutate {
        require(mtu in MIN_MTU..MAX_MTU) { "Unsupported VPN MTU: $mtu" }
        setMtu(mtu)
    }

    fun addAddress(
        address: String,
        prefixLength: Int,
        gateway: String,
        ipv6: Boolean,
        net30: Boolean
    ) = mutate {
        val parsedAddress = parseNumericAddress(address)
        val addressIsIpv6 = parsedAddress.address.size == 16
        require(addressIsIpv6 == ipv6) {
            "OpenVPN address-family metadata does not match the interface address."
        }
        require(prefixLength in 0..(if (ipv6) 128 else 32)) {
            "Invalid address prefix length."
        }

        if (gateway.isNotBlank()) {
            val parsedGateway = parseNumericAddress(gateway)
            require((parsedGateway.address.size == 16) == ipv6) {
                "OpenVPN gateway address family does not match the interface address."
            }
        }

        if (net30) {
            require(!ipv6 && prefixLength == 30 && gateway.isNotBlank()) {
                "OpenVPN net30 topology requires an IPv4 /30 address and peer."
            }
            val localBytes = parsedAddress.address
            val peerBytes = parseNumericAddress(gateway).address
            require(localBytes.size == 4 && peerBytes.size == 4) {
                "OpenVPN net30 topology only supports IPv4."
            }
            require((localBytes[0].toInt() and 0xff) == (peerBytes[0].toInt() and 0xff) &&
                (localBytes[1].toInt() and 0xff) == (peerBytes[1].toInt() and 0xff) &&
                (localBytes[2].toInt() and 0xff) == (peerBytes[2].toInt() and 0xff) &&
                ((localBytes[3].toInt() and 0xfc) == (peerBytes[3].toInt() and 0xfc)) &&
                !localBytes.contentEquals(peerBytes)) {
                "OpenVPN net30 local and peer addresses must be distinct members of the same /30."
            }
        }

        // Android's Builder accepts the interface address/prefix but has no separate
        // point-to-point peer parameter. Validate OpenVPN's gateway/topology metadata
        // before applying the address; OpenVPN routes are installed by their own callbacks.
        addAddress(address, prefixLength)
        configuredFamilies += familyFor(ipv6)
    }

    fun addRoute(address: String, prefixLength: Int, ipv6: Boolean) = mutate {
        val parsedAddress = parseNumericAddress(address)
        require((parsedAddress.address.size == 16) == ipv6) {
            "OpenVPN route address-family metadata does not match the route address."
        }
        require(prefixLength in 0..(if (ipv6) 128 else 32)) {
            "Invalid route prefix length."
        }
        addRoute(address, prefixLength)
        configuredFamilies += familyFor(ipv6)
    }

    fun excludeRoute(address: String, prefixLength: Int, ipv6: Boolean) = mutate {
        val parsedAddress = parseNumericAddress(address)
        require((parsedAddress.address.size == 16) == ipv6) {
            "OpenVPN excluded-route address-family metadata does not match the route address."
        }
        require(prefixLength in 0..(if (ipv6) 128 else 32)) {
            "Invalid excluded-route prefix length."
        }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Safe route exclusions require Android 13 or newer; refusing a potentially leaky route configuration."
        }
        excludeRoute(IpPrefix(parsedAddress, prefixLength))
    }

    fun addDnsServer(address: String) = mutate {
        // DNS endpoints must be numeric literals; never trigger name resolution
        // while the VPN interface is being assembled.
        val parsedAddress = parseNumericAddress(address)
        addDnsServer(parsedAddress)
        configuredFamilies += if (parsedAddress.address.size == 16) {
            OsConstants.AF_INET6
        } else {
            OsConstants.AF_INET
        }
    }

    fun addSearchDomain(domain: String) = mutate {
        val normalizedDomain = validateSearchDomain(domain)
        addSearchDomain(normalizedDomain)
    }

    /**
     * Android's VPN builder accepts DNS search suffixes, not arbitrary text. Validate
     * DNS label boundaries and lengths before passing server-pushed values to the platform.
     * A single trailing root dot is accepted and removed for Android's API.
     */
    private fun validateSearchDomain(domain: String): String {
        require(domain.isNotEmpty() && domain == domain.trim()) {
            "DNS search domain must not be empty or contain surrounding whitespace."
        }
        val normalized = domain.removeSuffix(".")
        require(normalized.isNotEmpty() && normalized.length <= MAX_DNS_NAME_LENGTH &&
            !normalized.startsWith('.') && !normalized.endsWith('.') && !normalized.contains("..")) {
            "DNS search domain has an invalid total length or empty label."
        }
        val labels = normalized.split('.')
        require(labels.all { label ->
            label.length in 1..MAX_DNS_LABEL_LENGTH &&
                isAsciiAlphaNumeric(label.first()) &&
                isAsciiAlphaNumeric(label.last()) &&
                label.all { isAsciiAlphaNumeric(it) || it == '-' }
        }) {
            "DNS search domain contains an invalid DNS label."
        }
        return normalized
    }

    private fun isAsciiAlphaNumeric(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

    /**
     * Android enables a family implicitly when an address, route, or DNS server is added.
     * Builder.allowFamily() would instead allow otherwise-unconfigured traffic to escape over
     * the underlying network. Never call it. If OpenVPN explicitly blocks a family that has
     * already been configured, Android cannot represent that policy safely, so reject the TUN
     * setup rather than silently violating the profile's block-ipv4/block-ipv6 directive.
     */
    fun setAllowFamily(addressFamily: Int, allow: Boolean) = mutate {
        require(addressFamily == OsConstants.AF_INET || addressFamily == OsConstants.AF_INET6) {
            "Unsupported address family: $addressFamily"
        }
        // Android enables configured families implicitly and cannot later block them.
        // Reject either contradictory request rather than silently weakening OpenVPN policy.
        if (!allow && addressFamily in configuredFamilies) {
            error("Cannot block an address family already configured on the VPN interface.")
        }
        if (allow && addressFamily !in configuredFamilies) {
            error("Refusing to allow an unused address family outside the VPN tunnel.")
        }
        // Android blocks unconfigured families by default. Never call Builder.allowFamily().
    }

    /**
     * Establishes the configured interface and transfers descriptor ownership to the caller.
     * A null descriptor is treated as a hard failure; callers must not report CONNECTED.
     */
    fun establish(): Int = synchronized(lock) {
        check(!established) { "VPN interface has already been established." }
        val activeBuilder = checkNotNull(builder) { "VPN builder is closed." }
        val descriptor: ParcelFileDescriptor = checkNotNull(activeBuilder.establish()) {
            "Android could not establish the VPN interface."
        }
        established = true
        builder = null
        descriptor.detachFd()
    }

    /**
     * Accept only numeric IP literals. InetAddress.getByName() alone may perform DNS
     * resolution for hostnames, which is unsafe while configuring a VPN route.
     */
    private fun parseNumericAddress(address: String): InetAddress {
        require(address.isNotBlank()) { "IP address must not be blank." }
        if (address.contains(':')) {
            require(address.all { it in "0123456789abcdefABCDEF:." }) {
                "IPv6 address must be a numeric literal."
            }
            require(address.count { it == ':' } >= 2) { "Invalid IPv6 address." }
            return InetAddress.getByName(address)
        }

        val octets = address.split('.')
        require(octets.size == 4 && octets.all { octet ->
            val value = octet.toIntOrNull()
            octet.isNotEmpty() && octet.all(Char::isDigit) && value != null && value in 0..255
        }) { "IPv4 address must be a numeric literal." }
        return InetAddress.getByAddress(
            octets.map { it.toInt().toByte() }.toByteArray()
        )
    }

    private fun familyFor(ipv6: Boolean): Int =
        if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET

    private inline fun mutate(block: VpnService.Builder.() -> Unit) = synchronized(lock) {
        check(!established) { "VPN interface is already established." }
        val activeBuilder = checkNotNull(builder) { "VPN builder is closed." }
        activeBuilder.block()
    }

    override fun close() = synchronized(lock) {
        // Builder has no close operation. Once establish() succeeds, the detached descriptor
        // belongs to the native OpenVPN core and must be closed by that owner during teardown.
        builder = null
    }

    private companion object {
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
        const val MAX_DNS_LABEL_LENGTH = 63
        const val MAX_DNS_NAME_LENGTH = 253
    }
}
