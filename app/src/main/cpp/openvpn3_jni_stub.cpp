#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpngate_vpn_OpenVpn3Native_nativeCoreAvailable(JNIEnv *, jobject) {
    return JNI_FALSE;
}