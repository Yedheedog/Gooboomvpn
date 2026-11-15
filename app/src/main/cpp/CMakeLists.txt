cmake_minimum_required(VERSION 3.22.1)
project(openvpn3_jni LANGUAGES CXX)

# OpenVPN 3 at the pinned revision requires C++20.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# This target contains the Android ClientAPI adapter, JNI callbacks, TUN
# builder bridge, and VpnService socket-protection integration.
add_library(openvpn3_jni SHARED openvpn3_jni_stub.cpp)
find_library(log-lib log)
# OpenVPN 3's add_core_dependencies() uses the plain target_link_libraries()
# signature for this target, so keep every link declaration on that target
# in the plain signature too (CMake forbids mixing the two signatures).
target_link_libraries(openvpn3_jni ${log-lib})
target_compile_features(openvpn3_jni PRIVATE cxx_std_20)

set(OPENVPN3_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/openvpn3")
if(NOT EXISTS "${OPENVPN3_ROOT}/client/ovpncli.cpp")
    message(FATAL_ERROR
        "Pinned OpenVPN 3 source is missing. Run scripts/fetch-openvpn3.sh first.")
endif()

if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(VPNGATE_VCPKG_TRIPLET "arm64-android")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(VPNGATE_VCPKG_TRIPLET "x64-android")
    else()
        message(FATAL_ERROR "No pinned native dependency triplet for ${ANDROID_ABI}")
    endif()

    get_filename_component(VPNGATE_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
    set(VPNGATE_DEPS_ROOT "${VPNGATE_REPO_ROOT}/.native-deps/${VPNGATE_VCPKG_TRIPLET}/${VPNGATE_VCPKG_TRIPLET}")
    if(NOT EXISTS "${VPNGATE_DEPS_ROOT}/include")
        message(FATAL_ERROR
            "OpenVPN 3 dependencies for ${ANDROID_ABI} are missing. "
            "Run scripts/install-android-native-deps.sh.")
    endif()

    # Android's toolchain sets CMAKE_FIND_ROOT_PATH_MODE_* to ONLY, which
    # re-roots host-side vcpkg prefixes under the NDK sysroot. Pin the
    # dependency locations explicitly so Findasio/Findlz4 cannot miss them.
    set(ASIO_INCLUDE_DIR "${VPNGATE_DEPS_ROOT}/include" CACHE PATH "vcpkg Asio headers" FORCE)
    set(LZ4_INCLUDE_DIR "${VPNGATE_DEPS_ROOT}/include" CACHE PATH "vcpkg LZ4 headers" FORCE)
    set(LZ4_LIBRARY "${VPNGATE_DEPS_ROOT}/lib/liblz4.a" CACHE FILEPATH "vcpkg LZ4 library" FORCE)
    set(fmt_DIR "${VPNGATE_DEPS_ROOT}/share/fmt" CACHE PATH "vcpkg fmt package config" FORCE)

    list(PREPEND CMAKE_PREFIX_PATH "${VPNGATE_DEPS_ROOT}")
    set(ENV{PKG_CONFIG_LIBDIR} "${VPNGATE_DEPS_ROOT}/lib/pkgconfig")
    set(ENV{PKG_CONFIG_PATH} "${VPNGATE_DEPS_ROOT}/lib/pkgconfig")

    # Android's pthread API is supplied by bionic/libc, not a separate libpthread.
    # The upstream generic UNIX helper links pthread, so satisfy that dependency
    # with an interface target on Android.
    if(NOT TARGET pthread)
        add_library(pthread INTERFACE IMPORTED)
    endif()
endif()

list(PREPEND CMAKE_MODULE_PATH "${OPENVPN3_ROOT}/cmake")
set(USE_MBEDTLS ON CACHE BOOL "Use mbedTLS for the Android OpenVPN 3 core" FORCE)
include("${OPENVPN3_ROOT}/cmake/findcoredeps.cmake")

target_sources(openvpn3_jni PRIVATE "${OPENVPN3_ROOT}/client/ovpncli.cpp")
target_include_directories(openvpn3_jni PRIVATE "${OPENVPN3_ROOT}/client")
add_core_dependencies(openvpn3_jni)

if(ANDROID)
    # OpenVPN's pinned findcoredeps.cmake discovers mbedTLS through pkg-config,
    # but its imported targets currently expose -l names without the vcpkg
    # library search directories on Android. Supply both vcpkg configurations
    # explicitly so the NDK linker can resolve the three static mbedTLS libs.
    target_link_directories(openvpn3_jni PRIVATE
        "${VPNGATE_DEPS_ROOT}/debug/lib"
        "${VPNGATE_DEPS_ROOT}/lib"
    )
endif()

# Source compilation proves the JNI adapter and OpenVPN core link together.
# It does not verify a live relay handshake, packet forwarding, or leak behavior;
# those require end-to-end tests on a physical Android device.
