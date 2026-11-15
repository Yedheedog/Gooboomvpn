# BUST THE GATE

**Ads-free Android OpenVPN connector for public VPN Gate volunteer relays.**

BUST THE GATE is an Android VPN client that discovers public VPN Gate-compatible relay profiles and connects through the embedded OpenVPN 3 core.

## Current release

The first public release is being prepared as **v1.0.0**. Once published, the official APKs and SHA-256 checksums will be available on the [GitHub Releases page](https://github.com/Yedheedog/Gooboomvpn/releases).

- Android package: `com.example.vpngate`
- Minimum Android version: Android 8.0 (API 26)
- Target SDK: Android 15 (API 35)
- Supported ABIs: `arm64-v8a` and `x86_64`
- Release APK is signed with the project's configured PKCS12 release key.
- Debug and release APKs are built and published separately.

## Features

- VPN Gate relay discovery and refresh.
- Add, enable, disable, and remove HTTPS VPN Gate-compatible CSV sources. Arbitrary provider formats are not supported.
- Import individual `.ovpn` profiles; imported profile data is encrypted at rest with an Android Keystore AES-GCM key.
- Combine enabled sources, deduplicate relays, and show source labels.
- Country filtering for the relay list, automatic selection, and failover candidates.
- Manual relay selection and sorting by lowest ping, highest published speed, or balanced metadata.
- Latency-first automatic relay selection.
- Automatic failover attempts for eligible connection failures (up to three alternate relays).
- Connection recovery when the selected underlying physical network changes.
- Connected-session timer and upload/download byte totals from OpenVPN TUN statistics.

### Known limitations

- Live measured latency/throughput probes are not yet used for ranking; displayed sorting uses published relay metadata.
- Automatic failover and Wi-Fi/mobile network recovery have not been exhaustively validated across Android devices and OEM versions.
- The app has been confirmed to establish a real VPN tunnel on a physical Android device. That device confirmation does not imply every relay, network transition, DNS configuration, or traffic-leak scenario has been independently tested.
- Public VPN Gate relays are volunteer-operated and may be slow, unavailable, or untrusted.

## Download and install

1. Open [GitHub Releases](https://github.com/Yedheedog/Gooboomvpn/releases).
2. Download the `-release.apk` asset for the version you want.
3. Verify its SHA-256 hash against `SHA256SUMS.txt` if desired.
4. Install the APK on Android. You may need to allow your browser or file manager to install apps from that source.
5. Review Android's VPN permission prompt and approve it to connect.

The debug APK is intended for development/testing. Most users should install the release APK.

## Build from source

Requirements include Android SDK platform 35, Android NDK 27.2.12479018, CMake 3.22.1, JDK 17, and Gradle 8.9.

Fetch the pinned OpenVPN 3 source and build native dependencies:

```sh
bash scripts/fetch-openvpn3.sh
bash scripts/install-android-native-deps.sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Release signing is enabled when all four environment variables are configured:

- `RELEASE_KEYSTORE_PATH`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Never commit the release keystore or its passwords to the repository. Keep secure offline backups of the signing key.

## Safety and privacy

VPN Gate relays are operated by volunteers, not by the app developer. Relay operators may be able to observe traffic metadata and unencrypted traffic. Do not treat public relays as trusted for sensitive activity. Use HTTPS and other end-to-end encryption, and avoid transmitting sensitive information through untrusted relays.

The app must not claim a tunnel is active until OpenVPN reports a successful connection and the Android TUN interface is established.

## OpenVPN 3 integration

The upstream OpenVPN 3 source revision is pinned in `OPENVPN3_REVISION`. Fetch it reproducibly with:

```sh
bash scripts/fetch-openvpn3.sh
```

The Android native integration uses JNI and builds for `arm64-v8a` and `x86_64`.

## Project status

The core VPN connection has been confirmed working on a physical Android device. CI unit tests and native/Kotlin compilation provide build verification, but they do not replace device-level testing of relay connectivity, DNS routing, IPv4/IPv6 leak behavior, disconnect/revoke cleanup, failover, or Wi-Fi/mobile recovery.
