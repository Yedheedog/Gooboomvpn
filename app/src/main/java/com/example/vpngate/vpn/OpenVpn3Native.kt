package com.example.vpngate.vpn

/** JNI boundary for the pinned OpenVPN 3 core and Android service callbacks. */
object OpenVpn3Native {
    private val loaded: Boolean = try {
        System.loadLibrary("openvpn3_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun isCoreAvailable(): Boolean = loaded && nativeCoreAvailable()

    fun start(service: VpnGateVpnService, profile: ByteArray): String? {
        if (!loaded) return "OpenVPN 3 native library could not be loaded."
        return nativeStart(service, profile)
    }

    fun stop() {
        if (loaded) nativeStop()
    }

    fun tunStats(): LongArray = if (loaded) nativeTunStats() else longArrayOf(0L, 0L)

    private external fun nativeCoreAvailable(): Boolean
    private external fun nativeStart(service: VpnGateVpnService, profile: ByteArray): String?
    private external fun nativeStop()
    private external fun nativeTunStats(): LongArray
}
