# VPN Gate Connect

Android-only VPN Gate client using public volunteer VPN Gate relays and OpenVPN profiles.

## Project status

This repository is under active development. The OpenVPN 3 native core, JNI adapter, Android TUN builder bridge, and service lifecycle are being integrated. A successful CI compile is not proof of a working VPN tunnel; real-device connection, traffic, DNS, and leak testing are still required before calling it functional.

## Planned architecture

- Kotlin Android app and VPN Gate relay discovery
- Fastest-server ranking based on relay metadata
- Android `VpnService` lifecycle and foreground notification
- OpenVPN 3 core via JNI
- Android TUN builder and protected transport sockets
- Explicit connection-state reporting and reliable disconnect cleanup

## Post-QC feature backlog

These are planned enhancements to revisit after the core VPN passes quality control (QC):

- [ ] **Add VPN Source button:** place a plus (+) icon immediately to the left of the existing refresh button in the top app bar.
- [ ] **Add provider/server-list URL:** let users add another VPN provider or compatible server-list source, then enable, disable, and remove individual sources.
- [ ] **Import OpenVPN profile:** allow users to import an individual `.ovpn` configuration file as a VPN source, with source management controls.
- [ ] **Multi-source server discovery:** combine servers from enabled sources and make the source/provider visible for each server; only include compatible and validated sources in automatic selection.
- [ ] **Automatic failover:** move to another suitable relay when the active relay fails.
- [ ] **Country filtering:** allow users to limit which countries are considered by fastest-server selection.
- [ ] **Improved server ranking:** supplement published VPN Gate metadata with measured connection performance where practical.
- [ ] **Connection recovery:** handle Wi-Fi/mobile-data changes and reconnect safely.

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
