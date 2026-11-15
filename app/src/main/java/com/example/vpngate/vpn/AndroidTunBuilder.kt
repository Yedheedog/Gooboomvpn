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
        addDnsServer(parseNumericAddress(address))
    }

    fun addSearchDomain(domain: String) = mutate {
        require(domain.isNotBlank() && !domain.contains(' ')) { "Invalid DNS search domain." }
        addSearchDomain(domain)
    }

    /**
     * OpenVPN's "allow" means the address family may use the underlying network when it
     * is not routed through the tunnel. Android's allowFamily() has exactly that bypass
     * effect. Do not invoke it: Android already permits a family when an address, route,
     * or DNS server is added, and blocks an otherwise-unconfigured family by default.
     * This intentionally fails closed rather than allowing IPv6 to escape an IPv4-only VPN.
     */
    fun setAllowFamily(addressFamily: Int, allow: Boolean) = mutate {
        require(addressFamily == OsConstants.AF_INET || addressFamily == OsConstants.AF_INET6) {
            "Unsupported address family: $addressFamily"
        }
        // Intentionally do not call Builder.allowFamily(); routes/addresses/DNS added
        // through this adapter implicitly enable the family inside the VPN.
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
    }
}
