#include <jni.h>

#include <memory>

#include "ovpncli.hpp"

// First native integration milestone: compile a concrete OpenVPN 3 client
// implementation against the pinned ClientAPI. The Android TunBuilder and
// protected-socket callbacks are deliberately fail-closed until their JNI
// handoff is implemented; this object must not be used to claim a VPN session.
namespace {

class AndroidOpenVPNClient final : public openvpn::ClientAPI::OpenVPNClient {
  public:
    bool pause_on_connection_timeout() override {
        return false;
    }

    void event(const openvpn::ClientAPI::Event &) override {
        // Event-to-Kotlin delivery is added with the lifecycle bridge.
    }

    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {
        // No application control channel is exposed in this milestone.
    }

    void log(const openvpn::ClientAPI::LogInfo &) override {
        // Sensitive profile and transport details must not be logged.
    }

    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &) override {
        // VPN Gate profiles use inline credentials; external PKI is unsupported.
    }

    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &) override {
        // Fail closed: no external signing key is available.
    }

    bool socket_protect(openvpn_io::detail::socket_type,
                        std::string,
                        bool) override {
        // Never allow a transport socket to connect until Android
        // VpnService.protect(fd) is wired through the JNI callback.
        return false;
    }
};

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeCoreAvailable(JNIEnv *, jobject) {
    // Keep the public availability gate closed until TUN setup, socket
    // protection, worker-thread connect/stop, and event delivery are complete.
    return JNI_FALSE;
}
