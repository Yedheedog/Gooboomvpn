# VPN Gate Connect

Android-only VPN Gate client using public volunteer VPN Gate relays and OpenVPN profiles.

## Project status

The pinned OpenVPN 3 core is now compiled and linked into the Android JNI library for `arm64-v8a` and `x86_64`. The repository includes a real `ClientAPI::OpenVPNClient` subclass, JNI callbacks, an Android `VpnService.Builder` bridge, protected transport sockets, detached TUN descriptor handoff, and a serialized connect/stop lifecycle. OpenVPN core owns packet I/O through the handed-off TUN descriptor.

CI runs relay-discovery unit tests and compiles Kotlin plus the native library for both configured ABIs without packaging APKs. This is source/build verification only: the app has **not yet been verified on a physical Android device**, so a successful compile must not be treated as proof of a working VPN tunnel. Real relay handshake, traffic, DNS, IPv4/IPv6 leak, disconnect, and revoke testing are still required.

## Planned architecture

- Kotlin Android app and VPN Gate relay discovery
- Fastest-server ranking based on relay metadata
- Android `VpnService` lifecycle and foreground notification
- OpenVPN 3 core via JNI
- Android TUN builder and protected transport sockets
- Explicit connection-state reporting and reliable disconnect cleanup

## Feature implementation status

The feature branch `feature/readme-all-features` now contains the following coordinated implementation:

- [x] **Add VPN Source button:** the plus (+) icon is immediately to the left of Refresh.
- [x] **Add provider/server-list URL:** users can add, enable, disable, and remove HTTPS VPN Gate-compatible CSV sources. Arbitrary provider formats are not yet supported.
- [x] **Import OpenVPN profile:** individual `.ovpn` profiles can be imported and managed. Profile data is encrypted at rest with an Android Keystore AES-GCM key.
- [x] **Multi-source server discovery:** enabled compatible CSV sources are combined with the built-in VPN Gate list, deduplicated, and labeled by source. Imported profiles appear as individual entries.
- [x] **Automatic failover:** unexpected fatal or terminal relay events trigger up to three alternate relay attempts, excluding already-tried relays. Recovery keeps the service foreground and respects the active country filter.
- [x] **Country filtering:** country restrictions apply to the displayed list, automatic selection, and failover candidates.
- [x] **Available-list sorting:** Lowest ping, Highest speed, and Balanced sorting are separate from the default latency-first automatic selection.
- [ ] **Improved server ranking:** live measured latency/throughput probes are not yet connected; current ranking uses published relay metadata.
- [x] **Connection recovery:** the service monitors validated non-VPN networks, updates the VPN's underlying-network association, and restarts the current relay when the selected underlying network changes.

These are source-level implementations on the feature branch, not a release claim. Android CI compilation/tests and physical-device testing are still required, especially for real failover, Wi-Fi/mobile transitions, and traffic-leak behavior.

**UI placement note:** the Add VPN Source (+) icon belongs directly to the left of the refresh icon. Preserve the existing refresh action.

## Build requirements

Android Studio, Android SDK, Android NDK, CMake, and a compatible JDK are required. CI currently compiles Kotlin and native libraries; APK packaging is reserved for meaningful device-testing milestones.

## Safety and privacy

VPN Gate relays are operated by volunteers, not by this app's developer. Relay operators may be able to observe traffic metadata and unencrypted traffic. Do not treat public relays as trusted for sensitive activity. The app must not claim a tunnel is active until the OpenVPN core reports a successful connection and the TUN interface is established.

## OpenVPN 3 integration

The upstream OpenVPN 3 source revision is pinned in `OPENVPN3_REVISION`. Fetch it reproducibly with:

```sh
bash scripts/fetch-openvpn3.sh
```

The script stages upstream source under `app/src/main/cpp/third_party/openvpn3` and verifies the exact commit.

## Android TUN adapter

The Android-side `AndroidTunBuilder` helper wraps `VpnService.Builder` operations for session name, MTU, interface addresses, included and excluded routes, DNS servers and search domains, and address-family allow/block settings. Allowing an address family is opt-in because Android's `allowFamily()` permits that traffic to bypass the VPN. Route exclusions intentionally fail closed below Android 13 because the platform cannot represent them safely through the same API.

## Validation milestone

Before release, build and test on a physical Android device. Verify real relay connection, public-IP changes, DNS routing, IPv4/IPv6 behavior, reconnect, revoke/disconnect cleanup, network changes, and absence of traffic leaks. Do not treat CI compilation alone as proof of a functioning VPN.

## Android native dependency build

Install per-ABI native dependencies with `bash scripts/install-android-native-deps.sh`; see [NATIVE_DEPENDENCIES.md](NATIVE_DEPENDENCIES.md) for the pinned vcpkg baseline and ABI mapping. CI performs Kotlin and native-library compilation and does not routinely package APKs.
