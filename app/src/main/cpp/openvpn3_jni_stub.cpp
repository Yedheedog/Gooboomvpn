#include <jni.h>

#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <thread>
#include <atomic>
#include <exception>
#include <algorithm>
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
            event_method_ = env->GetMethodID(
                service_class, "onNativeEvent",
                "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
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
                tun_builder_class_, "addAddress",
                "(Ljava/lang/String;ILjava/lang/String;ZZ)V");
            route_method_ = env->GetMethodID(
                tun_builder_class_, "addRoute", "(Ljava/lang/String;IZ)V");
            exclude_route_method_ = env->GetMethodID(
                tun_builder_class_, "excludeRoute", "(Ljava/lang/String;IZ)V");
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

    bool callbacks_ready() const {
        return service_ != nullptr && protect_method_ != nullptr &&
               event_method_ != nullptr && tun_builder_class_ != nullptr &&
               tun_builder_ctor_ != nullptr && session_method_ != nullptr &&
               mtu_method_ != nullptr && address_method_ != nullptr &&
               route_method_ != nullptr && exclude_route_method_ != nullptr &&
               dns_method_ != nullptr && search_domain_method_ != nullptr &&
               allow_family_method_ != nullptr && establish_method_ != nullptr &&
               close_method_ != nullptr;
    }

    bool pause_on_connection_timeout() override { return false; }

    void event(const openvpn::ClientAPI::Event &event) override {
        emit_event(event.name, event.info, event.error, event.fatal);
    }
    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {}
    void log(const openvpn::ClientAPI::LogInfo &) override {}

    void emit_status(const openvpn::ClientAPI::Status &status) {
        if (status.error) emit_event("CONNECT_RETURN", status.message, true, true);
        else emit_event("CONNECT_RETURN", status.message, false, false);
    }

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
                                 bool ipv6,
                                 bool net30) override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || address_method_ == nullptr) return false;
        jstring jaddress = env->NewStringUTF(address.c_str());
        jstring jgateway = env->NewStringUTF(gateway.c_str());
        if (jaddress == nullptr || jgateway == nullptr || env->ExceptionCheck()) {
            clear_exception(env);
            if (jaddress != nullptr) env->DeleteLocalRef(jaddress);
            if (jgateway != nullptr) env->DeleteLocalRef(jgateway);
            return false;
        }
        env->CallVoidMethod(tun_builder_, address_method_, jaddress,
                            static_cast<jint>(prefix_length), jgateway,
                            ipv6 ? JNI_TRUE : JNI_FALSE,
                            net30 ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(jaddress);
        env->DeleteLocalRef(jgateway);
        return !clear_exception(env);
    }

    // Android VpnService.Builder has no per-route metric API; preserve route
    // reachability and document that OpenVPN metrics cannot be represented here.
    bool tun_builder_set_route_metric_default(int) override { return true; }

    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override {
        // Android's Builder can install default routes, but this adapter does not
        // yet implement OpenVPN's DHCP/DNS bypass or block-local redirect flags.
        // Reject those policies instead of silently weakening the requested routing.
        // Keep these bit values aligned with OpenVPN 3's RedirectGatewayFlags
        // enum (rgopt.hpp), without including that header in this Android TU.
        constexpr unsigned int RG_ENABLE = 1U << 0;
        constexpr unsigned int RG_REROUTE_GW = 1U << 1;
        constexpr unsigned int RG_LOCAL = 1U << 2;
        constexpr unsigned int RG_AUTO_LOCAL = 1U << 3;
        constexpr unsigned int RG_DEF1 = 1U << 4;
        constexpr unsigned int RG_BYPASS_DHCP = 1U << 5;
        constexpr unsigned int RG_BYPASS_DNS = 1U << 6;
        constexpr unsigned int RG_BLOCK_LOCAL = 1U << 7;
        constexpr unsigned int RG_IPv4 = 1U << 8;
        constexpr unsigned int RG_IPv6 = 1U << 9;
        const unsigned int unsupported_flags =
            RG_BYPASS_DHCP | RG_BYPASS_DNS | RG_BLOCK_LOCAL;
        const unsigned int known_flags =
            RG_ENABLE | RG_REROUTE_GW | RG_LOCAL | RG_AUTO_LOCAL | RG_DEF1 |
            RG_BYPASS_DHCP | RG_BYPASS_DNS | RG_BLOCK_LOCAL | RG_IPv4 | RG_IPv6;
        if ((flags & unsupported_flags) != 0 || (flags & ~known_flags) != 0) return false;
        if (ipv4) {
            // OpenVPN's "def1" mode deliberately preserves the existing default
            // route and installs two more-specific IPv4 halves instead. A single
            // /0 route is not equivalent when the Android device already has
            // routing state that should remain the fallback.
            if ((flags & RG_DEF1) != 0) {
                if (!call_string_int_bool(route_method_, "0.0.0.0", 1, false)) return false;
                if (!call_string_int_bool(route_method_, "128.0.0.0", 1, false)) return false;
            } else if (!call_string_int_bool(route_method_, "0.0.0.0", 0, false)) {
                return false;
            }
        }
        if (ipv6 && !call_string_int(route_method_, "::", 0)) return false;
        return true;
    }

    bool tun_builder_add_route(const std::string &address,
                               int prefix_length,
                               int,
                               bool ipv6) override {
        return call_string_int_bool(route_method_, address, prefix_length, ipv6);
    }

    bool tun_builder_exclude_route(const std::string &address,
                                   int prefix_length,
                                   int,
                                   bool ipv6) override {
        return call_string_int_bool(exclude_route_method_, address, prefix_length, ipv6);
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
    void emit_event(const std::string &name, const std::string &info,
                    bool error, bool fatal) {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (env == nullptr || service_ == nullptr || event_method_ == nullptr) return;
        jstring jname = env->NewStringUTF(name.c_str());
        jstring jinfo = env->NewStringUTF(info.c_str());
        if (jname == nullptr || jinfo == nullptr || env->ExceptionCheck()) {
            clear_exception(env);
            if (jname != nullptr) env->DeleteLocalRef(jname);
            if (jinfo != nullptr) env->DeleteLocalRef(jinfo);
            return;
        }
        env->CallVoidMethod(service_, event_method_, jname, jinfo,
                            error ? JNI_TRUE : JNI_FALSE,
                            fatal ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jinfo);
        clear_exception(env);
    }

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

    bool call_string_int_bool(jmethodID method, const std::string &value,
                              int number, bool flag) {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (!ready(env) || method == nullptr) return false;
        jstring text = env->NewStringUTF(value.c_str());
        if (text == nullptr || env->ExceptionCheck()) {
            clear_exception(env);
            return false;
        }
        env->CallVoidMethod(tun_builder_, method, text, static_cast<jint>(number),
                            flag ? JNI_TRUE : JNI_FALSE);
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
    jmethodID event_method_ = nullptr;
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

std::mutex g_client_mutex;
std::shared_ptr<AndroidOpenVPNClient> g_client;
std::thread g_connect_worker;
std::atomic<bool> g_connect_running{false};

jstring make_java_string(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeCoreAvailable(JNIEnv *, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeStart(
    JNIEnv *env, jobject, jobject service, jbyteArray profile_bytes) {
    if (env == nullptr || service == nullptr || profile_bytes == nullptr) {
        return make_java_string(env, "Missing VPN service or OpenVPN profile.");
    }

    const jsize length = env->GetArrayLength(profile_bytes);
    if (length <= 0) return make_java_string(env, "The OpenVPN profile is empty.");
    std::vector<jbyte> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(profile_bytes, 0, length, bytes.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return make_java_string(env, "Could not read the OpenVPN profile.");
    }
    std::string content(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    std::fill(bytes.begin(), bytes.end(), 0);

    auto client = std::make_shared<AndroidOpenVPNClient>(env, service);
    if (!client->callbacks_ready()) {
        return make_java_string(env,
            "Android VPN callbacks could not be initialized; refusing to start OpenVPN.");
    }
    openvpn::ClientAPI::Config config;
    config.content = content;
    std::fill(content.begin(), content.end(), '\0');

    const openvpn::ClientAPI::EvalConfig evaluated = client->eval_config(config);
    // The config copy can contain inline certificates and private-key material.
    std::fill(config.content.begin(), config.content.end(), '\\0');
    if (evaluated.error) {
        return make_java_string(env, evaluated.message.empty()
            ? "OpenVPN rejected the selected profile." : evaluated.message);
    }
    if (evaluated.externalPki || evaluated.privateKeyPasswordRequired ||
        !evaluated.staticChallenge.empty()) {
        return make_java_string(env,
            "This relay profile requires external key, private-key password, or challenge handling that the app does not support yet.");
    }

    // VPN Gate's published OpenVPN setup uses the shared relay credentials
    // "vpn" / "vpn" when a profile requests username/password authentication.
    if (!evaluated.autologin) {
        openvpn::ClientAPI::ProvideCreds credentials;
        credentials.username = evaluated.userlockedUsername.empty()
            ? "vpn" : evaluated.userlockedUsername;
        credentials.password = "vpn";
        const openvpn::ClientAPI::Status credential_status =
            client->provide_creds(credentials);
        std::fill(credentials.username.begin(), credentials.username.end(), '\\0');
        std::fill(credentials.password.begin(), credentials.password.end(), '\\0');
        if (credential_status.error) {
            return make_java_string(env, credential_status.message.empty()
                ? "OpenVPN rejected the VPN Gate relay credentials."
                : credential_status.message);
        }
    }

    std::thread previous_worker;
    {
        std::lock_guard<std::mutex> lock(g_client_mutex);
        if (g_connect_running.load()) {
            return make_java_string(env, "A VPN connection is already starting or running.");
        }
        if (g_connect_worker.joinable()) previous_worker = std::move(g_connect_worker);
        g_client.reset();
    }
    if (previous_worker.joinable()) previous_worker.join();

    {
        std::lock_guard<std::mutex> lock(g_client_mutex);
        if (g_connect_running.load()) {
            return make_java_string(env, "A VPN connection is already starting or running.");
        }
        g_client = client;
        g_connect_running.store(true);
        try {
            g_connect_worker = std::thread([client]() {
                try {
                    const openvpn::ClientAPI::Status status = client->connect();
                    client->emit_status(status);
                } catch (const std::exception &e) {
                    openvpn::ClientAPI::Status failure;
                    failure.error = true;
                    failure.message = std::string("OpenVPN worker exception: ") + e.what();
                    client->emit_status(failure);
                } catch (...) {
                    openvpn::ClientAPI::Status failure;
                    failure.error = true;
                    failure.message = "OpenVPN worker failed with an unknown native exception.";
                    client->emit_status(failure);
                }
                g_connect_running.store(false);
            });
        } catch (const std::exception &e) {
            g_connect_running.store(false);
            g_client.reset();
            return make_java_string(env, std::string("Could not start OpenVPN worker: ") + e.what());
        }
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeStop(JNIEnv *, jobject) {
    std::thread worker;
    {
        std::lock_guard<std::mutex> lock(g_client_mutex);
        if (g_client) g_client->stop();
        if (g_connect_worker.joinable()) worker = std::move(g_connect_worker);
        g_client.reset();
    }
    if (worker.joinable()) worker.join();
    // The connect worker owns the running-state transition. A second stop call
    // may find the worker already moved to another caller; it must not clear the
    // flag while that first caller is still joining the live worker.
}
