# Android native dependencies

The OpenVPN 3 source is pinned by `OPENVPN3_REVISION`. Its Android native
dependencies are resolved through the root vcpkg manifest:

- Asio, using the patched overlay port shipped with the pinned OpenVPN 3 source
- fmt
- LZ4
- mbedTLS 3.x

The vcpkg registry baseline is pinned in `vcpkg.json`. The bootstrap script
also checks out the matching vcpkg commit before installing packages, so
dependency resolution is repeatable.

## Install

After installing Android NDK 27.2.12479018 and setting `ANDROID_NDK_HOME`,
run:

```sh
bash scripts/fetch-openvpn3.sh
bash scripts/install-android-native-deps.sh
```

The script installs separate dependency trees for:

| Android ABI | vcpkg triplet |
| --- | --- |
| arm64-v8a | arm64-android |
| x86_64 | x64-android |

32-bit `armeabi-v7a` is currently excluded. The pinned vcpkg community
triplet for that ABI fails compiler detection with the selected NDK, and
modern Android devices are predominantly 64-bit. It can be reconsidered if
32-bit device support becomes a requirement.

The CMake target consumes the matching tree based on `ANDROID_ABI`.

## Scope

These are dependency and core-compilation foundations. The JNI client adapter,
Android `TunBuilderBase` callback bridge, socket protection, packet forwarding,
and connection lifecycle still need to be implemented. The app must continue
failing closed and must not report a connected VPN until those pieces are real.
