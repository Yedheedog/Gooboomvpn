cmake_minimum_required(VERSION 3.22.1)
project(openvpn3_jni_stub LANGUAGES CXX)

# The pinned OpenVPN 3 client core requires C++20. Keep the JNI boundary on
# the same language level so the real core can be added without a later ABI
# or compiler-mode mismatch.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build a fail-closed JNI placeholder so the Kotlin app can verify native integration state.
# Replace this target with the upstream OpenVPN 3 Android build + SWIG/JNI client adapter before
# enabling real connections. This stub intentionally never claims that the core is available.
add_library(openvpn3_jni SHARED openvpn3_jni_stub.cpp)
find_library(log-lib log)
target_link_libraries(openvpn3_jni PRIVATE ${log-lib})
target_compile_features(openvpn3_jni PRIVATE cxx_std_17)