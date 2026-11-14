#include <jni.h>

#include <string>

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

// Owns the Android service reference used by OpenVPN's transport callback.
// The callback runs on the OpenVPN connect worker, not necessarily a Java thread.
class AndroidOpenVPNClient final : public openvpn::ClientAPI::OpenVPNClient {
  public:
    AndroidOpenVPNClient(JNIEnv *env, jobject service) {
        if (env != nullptr && service != nullptr) {
            service_ = env->NewGlobalRef(service);
            jclass service_class = env->GetObjectClass(service);
            if (service_class != nullptr) {
                protect_method_ = env->GetMethodID(service_class, "protectSocket", "(I)Z");
                env->DeleteLocalRef(service_class);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                protect_method_ = nullptr;
            }
        }
    }

    ~AndroidOpenVPNClient() override {
        ScopedJniEnv scoped(g_vm);
        JNIEnv *env = scoped.get();
        if (env != nullptr && service_ != nullptr) {
            env->DeleteGlobalRef(service_);
            service_ = nullptr;
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

  private:
    jobject service_ = nullptr;  // global reference, released in destructor
    jmethodID protect_method_ = nullptr;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeCoreAvailable(JNIEnv *, jobject) {
    // Keep availability disabled until TunBuilder setup, worker lifecycle, and
    // real event delivery are implemented and exercised by the Android build.
    return JNI_FALSE;
}
