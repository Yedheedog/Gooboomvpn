# VPN Gate Connect

Android-only VPN Gate client using public volunteer VPN Gate relays and OpenVPN profiles.

## Project status

This repository is being initialized from the VPN Gate Connect milestone scaffold. The current implementation is **not yet a functioning VPN tunnel**: the OpenVPN 3 native core and Android TUN adapter still need to be integrated and tested. The native JNI placeholder intentionally reports the core as unavailable, so the app must fail closed rather than falsely report a connection.

## Planned architecture

- Kotlin Android app and VPN Gate relay discovery
- Fastest-server ranking based on measured latency and server metadata
- Android `VpnService` lifecycle and foreground notification
- OpenVPN 3 core via JNI/SWIG
- Android TUN builder and protected transport sockets
- Explicit connection-state reporting and reliable disconnect cleanup

## Build requirements

Android Studio, Android SDK, Android NDK, CMake, and a compatible JDK are required. A successful Gradle build and real-device VPN test have not yet been completed in this environment.

## Safety and privacy

VPN Gate relays are operated by volunteers, not by this app's developer. Relay operators may be able to observe traffic metadata and unencrypted traffic. Do not treat public relays as trusted for sensitive activity. The app should not claim a tunnel is active until the OpenVPN core reports a successful connection and the TUN interface is established.

## OpenVPN 3 integration in progress

The upstream OpenVPN 3 source revision is pinned in `OPENVPN3_REVISION`. Fetch it reproducibly with:

```sh
bash scripts/fetch-openvpn3.sh
```

On a fresh Termux install, first run `pkg update && pkg install git`, clone this repository, then run the script from the repository root. The script stages upstream source under `app/src/main/cpp/third_party/openvpn3` and verifies the exact commit.

**Important:** source staging is not native integration. The current CMake target is still a fail-closed JNI stub. The OpenVPN 3 client API, Android `TunBuilderBase` implementation, socket protection callback, worker-thread lifecycle, and packet/TUN handoff must be implemented and built before the app can connect.

## Android TUN adapter foundation

The Android-side `AndroidTunBuilder` helper wraps `VpnService.Builder` operations for session name, MTU, interface addresses, included and excluded routes, DNS servers and search domains, and address-family allow/block settings. Allowing an address family is opt-in because Android's `allowFamily()` permits that traffic to bypass the VPN. Route exclusions intentionally fail closed below Android 13 because the platform cannot represent them safely through the same API. This is an adapter building block only: the JNI `OpenVPNClient` subclass does not yet call it, and the current native library remains a fail-closed placeholder.

## Next milestone

Vendor a pinned OpenVPN 3 client-core revision, implement the JNI bridge and `TunBuilderBase` adapter, then build and test on a physical Android device. Verify DNS routing, IPv4/IPv6 behavior, reconnect, revoke, and public-IP changes before release.


## Android native dependency build

The pinned OpenVPN 3 core is now compiled as part of the Android native target. Install its per-ABI dependencies with `bash scripts/install-android-native-deps.sh`; see [NATIVE_DEPENDENCIES.md](NATIVE_DEPENDENCIES.md) for the pinned vcpkg baseline and ABI mapping. CI performs Kotlin and native-library compilation only and does not package APKs. The JNI boundary remains fail-closed until the real OpenVPN client and Android TUN bridge are complete.
