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

    fun addAddress(address: String, prefixLength: Int) = mutate {
        val parsedAddress = parseNumericAddress(address)
        require(prefixLength in 0..(if (parsedAddress.address.size == 16) 128 else 32)) {
            "Invalid address prefix length."
        }
        addAddress(address, prefixLength)
    }

    fun addRoute(address: String, prefixLength: Int) = mutate {
        val parsedAddress = parseNumericAddress(address)
        require(prefixLength in 0..(if (parsedAddress.address.size == 16) 128 else 32)) {
            "Invalid route prefix length."
        }
        addRoute(address, prefixLength)
    }

    fun excludeRoute(address: String, prefixLength: Int) = mutate {
        val parsedAddress = parseNumericAddress(address)
        require(prefixLength in 0..(if (parsedAddress.address.size == 16) 128 else 32)) {
            "Invalid excluded-route prefix length."
        }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Safe route exclusions require Android 13 or newer; refusing a potentially leaky route configuration."
        }
        excludeRoute(IpPrefix(parsedAddress, prefixLength))
    }

    fun addDnsServer(address: String) = mutate {
        require(address.isNotBlank()) { "DNS server address must not be blank." }
        addDnsServer(address)
    }

    fun addSearchDomain(domain: String) = mutate {
        require(domain.isNotBlank() && !domain.contains(' ')) { "Invalid DNS search domain." }
        addSearchDomain(domain)
    }

    /**
     * Mirrors TunBuilderBase::tun_builder_set_allow_family. Android's allowFamily()
     * explicitly permits a family to bypass the VPN; false therefore means leave it blocked.
     */
    fun setAllowFamily(addressFamily: Int, allow: Boolean) = mutate {
        require(addressFamily == OsConstants.AF_INET || addressFamily == OsConstants.AF_INET6) {
            "Unsupported address family: $addressFamily"
        }
        if (allow) allowFamily(addressFamily)
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
