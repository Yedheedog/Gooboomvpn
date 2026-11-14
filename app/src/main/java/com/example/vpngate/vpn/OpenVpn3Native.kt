package com.example.vpngate.vpn

/** JNI loading boundary. The current packaged native shim intentionally reports unavailable. */
object OpenVpn3Native {
    private val loaded: Boolean = try {
        System.loadLibrary("openvpn3_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun isCoreAvailable(): Boolean = loaded && nativeCoreAvailable()

    private external fun nativeCoreAvailable(): Boolean
}