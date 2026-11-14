#include <jni.h>

#include <string>
#include <vector>
#include <sys/socket.h>

#include "ovpncli.hpp"

namespace {

JavaVM *g_vm = nullptr;

class ScopedJniEnv {
  public:
    explicit ScopedJniEnv(JavaVM *vm) : vm_(vm) {
        if (vm_ == nullptr) return;
        void *raw_env = nullptr;
        const jint result = vm_->GetEnv(&raw_env, JNI_VERSION_1_6);
        if (result == JNI_OK) {
            env_ = static_cast<JNIEnv *>(raw_env);
        } else if (result == JNI_EDETACHED &&
                   vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }

    ~ScopedJniEnv() {
        if (attached_ && vm_ != nullptr) vm_->DetachCurrentThread();
    }

    JNIEnv *get() const { return env_; }

  private:
    JavaVM *vm_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

class AndroidOpenVPNClient final : public openvpn::ClientAPI::OpenVPNClient {
  public:
    AndroidOpenVPNClient(JNIEnv *env, jobject service) {
        if (env == nullptr || service == nullptr) return;
        service_ = env->NewGlobalRef(service);

        jclass service_class = env->GetObjectClass(service);
        if (service_class != nullptr) {
            protect_method_ = env->GetMethodID(service_class, "protectSocket", "(I)Z");
            env->DeleteLocalRef(service_class);
        }

        // Resolve the Kotlin helper while called from the app's Java thread. The
        // class reference is global so callbacks on the native worker do not use
        // FindClass with the worker thread's (often boot) class loader.
        jclass local_builder_class =
            env->FindClass("com/example/vpngate/vpn/AndroidTunBuilder");
        if (local_builder_class != nullptr) {
            tun_builder_class_ =
                static_cast<jclass>(env->NewGlobalRef(local_builder_class));
            env->DeleteLocalRef(local_builder_class);
        }
        if (tun_builder_class_ != nullptr) {
            tun_builder_ctor_ = env->GetMethodID(
                tun_builder_class_, "<init>", "(Landroid/net/VpnService;)V");
            session_method_ = env->GetMethodID(
                tun_builder_class_, "setSessionName", "(Ljava/lang/String;)V");
            mtu_method_ = env->GetMethodID(
                tun_builder_class_, "setMtu", "(I)V");
            address_method_ = env->GetMethodID(
                tun_builder_class_, "addAddress", "(Ljava/lang/String;I)V");
            route_method_ = env->GetMethodID(
                tun_builder_class_, "addRoute", "(Ljava/lang/String;I)V");
            exclude_route_method_ = env->GetMethodID(
                tun_builder_class_, "excludeRoute", "(Ljava/lang/String;I)V");
            dns_method_ = env->GetMethodID(
                tun_builder_class_, "addDnsServer", "(Ljava/lang/String;)V");
            search_domain_method_ = env->GetMethodID(
                tun_builder_class_, "addSearchDomain", "(Ljava/lang/String;)V");
            allow_family_method_ = env->GetMethodID(
                tun_builder_class_, "setAllowFamily", "(IZ)V");
            establish_method_ = env->GetMethodID(
                tun_builder_class_, "establish", "()I");
            close_method_ = env->GetMethodID(
                tun_builder_class_, "close", "()V");
        }

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            clear_method_ids();
        }
    }

    ~AndroidOpenVPNClient() override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (env != nullptr) {
            release_tun_builder(env);
            if (tun_builder_class_ != nullptr) {
                env->DeleteGlobalRef(tun_builder_class_);
                tun_builder_class_ = nullptr;
            }
            if (service_ != nullptr) {
                env->DeleteGlobalRef(service_);
                service_ = nullptr;
            }
        }
    }

    bool pause_on_connection_timeout() override { return false; }

    void event(const openvpn::ClientAPI::Event &) override {}
    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {}
    void log(const openvpn::ClientAPI::LogInfo &) override {}

    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &request) override {
        request.error = true;
        request.errorText = "External PKI is not supported by VPN Gate Connect.";
    }

    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &request) override {
        request.error = true;
        request.errorText = "External PKI signing is not supported by VPN Gate Connect.";
    }

    bool socket_protect(openvpn_io::detail::socket_type socket,
                        std::string,
                        bool) override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (env == nullptr || service_ == nullptr || protect_method_ == nullptr) return false;

        const jboolean protected_socket =
            env->CallBooleanMethod(service_, protect_method_, static_cast<jint>(socket));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return protected_socket == JNI_TRUE;
    }

    // TunBuilderBase callbacks. Every unsupported setting fails the session
    // instead of silently dropping a server-pushed security/network option.
    bool tun_builder_new() override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (env == nullptr || service_ == nullptr || tun_builder_class_ == nullptr ||
            tun_builder_ctor_ == nullptr) return false;

        release_tun_builder(env);
        jobject local = env->NewObject(tun_builder_class_, tun_builder_ctor_, service_);
        if (local == nullptr || env->ExceptionCheck()) {
            clear_exception(env);
            return false;
        }
        tun_builder_ = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        return tun_builder_ != nullptr && !env->ExceptionCheck();
    }

    bool tun_builder_set_layer(int layer) override { return layer == 3; }

    bool tun_builder_set_remote_address(const std::string &, bool) override {
        return true;  // Android routes the transport socket through protectSocket().
    }

    bool tun_builder_add_address(const std::string &address,
                                 int prefix_length,
                                 const std::string &gateway,
                                 bool,
                                 bool net30) override {
        // Android Builder.addAddress has no gateway/net30 equivalent. Do not
        // pretend those semantics were applied.
        if (!gateway.empty() || net30) return false;
        return call_string_int(address_method_, address, prefix_length);
    }

    bool tun_builder_set_route_metric_default(int) override { return true; }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        if (ipv4 && !call_string_int(route_method_, "0.0.0.0", 0)) return false;
        if (ipv6 && !call_string_int(route_method_, "::", 0)) return false;
        return true;
    }

    bool tun_builder_add_route(const std::string &address,
                               int prefix_length,
                               int,
                               bool) override {
        return call_string_int(route_method_, address, prefix_length);
    }

    bool tun_builder_exclude_route(const std::string &address,
                                   int prefix_length,
                                   int,
                                   bool) override {
        return call_string_int(exclude_route_method_, address, prefix_length);
    }

    bool tun_builder_set_dns_options(const openvpn::DnsOptions &dns) override {
        for (const auto &entry : dns.servers) {
            const auto &server = entry.second;
            // Android's VpnService.Builder accepts plain IP resolvers only. A
            // custom port, DoH/DoT transport, or DNSSEC policy cannot be
            // represented faithfully, so fail closed for those profiles.
            if (server.transport != openvpn::DnsServer::Transport::Unset &&
                server.transport != openvpn::DnsServer::Transport::Plain) return false;
            if (server.dnssec != openvpn::DnsServer::Security::Unset) return false;
            for (const auto &address : server.addresses) {
                if (address.port != 0 || !call_string(dns_method_, address.address)) return false;
            }
            for (const auto &domain : server.domains) {
                if (!call_string(search_domain_method_, domain.domain)) return false;
            }
        }
        for (const auto &domain : dns.search_domains) {
            if (!call_string(search_domain_method_, domain.domain)) return false;
        }
        return true;
    }

    bool tun_builder_set_mtu(int mtu) override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || mtu_method_ == nullptr) return false;
        env->CallVoidMethod(tun_builder_, mtu_method_, static_cast<jint>(mtu));
        return !clear_exception(env);
    }

    bool tun_builder_set_session_name(const std::string &name) override {
        return call_string(session_method_, name);
    }

    bool tun_builder_add_proxy_bypass(const std::string &) override { return false; }
    bool tun_builder_set_proxy_auto_config_url(const std::string &) override { return false; }
    bool tun_builder_set_proxy_http(const std::string &, int) override { return false; }
    bool tun_builder_set_proxy_https(const std::string &, int) override { return false; }
    bool tun_builder_add_wins_server(const std::string &) override { return false; }

    bool tun_builder_set_allow_family(int family, bool allow) override {
        if (family != AF_INET && family != AF_INET6) return false;
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || allow_family_method_ == nullptr) return false;
        env->CallVoidMethod(tun_builder_, allow_family_method_,
                            static_cast<jint>(family),
                            allow ? JNI_TRUE : JNI_FALSE);
        return !clear_exception(env);
    }

    bool tun_builder_set_allow_local_dns(bool) override { return true; }

    int tun_builder_establish() override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || establish_method_ == nullptr) return -1;
        const jint fd = env->CallIntMethod(tun_builder_, establish_method_);
        if (clear_exception(env) || fd < 0) return -1;
        return static_cast<int>(fd);  // Ownership transfers to OpenVPN core.
    }

    bool tun_builder_persist() override { return false; }

    std::vector<std::string> tun_builder_get_local_networks(bool) override {
        return {};
    }

    void tun_builder_establish_lite() override {}

    void tun_builder_teardown(bool) override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (env != nullptr) release_tun_builder(env);
    }

  private:
    static bool clear_exception(JNIEnv *env) {
        if (env != nullptr && env->ExceptionCheck()) {
            env->ExceptionClear();
            return true;
        }
        return false;
    }

    void clear_method_ids() {
        tun_builder_ctor_ = nullptr;
        session_method_ = nullptr;
        mtu_method_ = nullptr;
        address_method_ = nullptr;
        route_method_ = nullptr;
        exclude_route_method_ = nullptr;
        dns_method_ = nullptr;
        search_domain_method_ = nullptr;
        allow_family_method_ = nullptr;
        establish_method_ = nullptr;
        close_method_ = nullptr;
    }

    bool ready(JNIEnv *env) const {
        return env != nullptr && service_ != nullptr && tun_builder_ != nullptr;
    }

    bool call_string(jmethodID method, const std::string &value) {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || method == nullptr) return false;
        jstring text = env->NewStringUTF(value.c_str());
        if (text == nullptr || env->ExceptionCheck()) {
            clear_exception(env);
            return false;
        }
        env->CallVoidMethod(tun_builder_, method, text);
        env->DeleteLocalRef(text);
        return !clear_exception(env);
    }

    bool call_string_int(jmethodID method, const std::string &value, int number) {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || method == nullptr) return false;
        jstring text = env->NewStringUTF(value.c_str());
        if (text == nullptr || env->ExceptionCheck()) {
            clear_exception(env);
            return false;
        }
        env->CallVoidMethod(tun_builder_, method, text, static_cast<jint>(number));
        env->DeleteLocalRef(text);
        return !clear_exception(env);
    }

    void release_tun_builder(JNIEnv *env) {
        if (env == nullptr || tun_builder_ == nullptr) return;
        if (close_method_ != nullptr) {
            env->CallVoidMethod(tun_builder_, close_method_);
            clear_exception(env);
        }
        env->DeleteGlobalRef(tun_builder_);
        tun_builder_ = nullptr;
    }

    jobject service_ = nullptr;
    jmethodID protect_method_ = nullptr;
    jclass tun_builder_class_ = nullptr;
    jobject tun_builder_ = nullptr;
    jmethodID tun_builder_ctor_ = nullptr;
    jmethodID session_method_ = nullptr;
    jmethodID mtu_method_ = nullptr;
    jmethodID address_method_ = nullptr;
    jmethodID route_method_ = nullptr;
    jmethodID exclude_route_method_ = nullptr;
    jmethodID dns_method_ = nullptr;
    jmethodID search_domain_method_ = nullptr;
    jmethodID allow_family_method_ = nullptr;
    jmethodID establish_method_ = nullptr;
    jmethodID close_method_ = nullptr;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeCoreAvailable(JNIEnv *, jobject) {
    // The adapter callbacks are implemented, but availability stays disabled
    // until native client creation, worker lifecycle, and real event delivery
    // are connected and exercised on an Android device.
    return JNI_FALSE;
}
