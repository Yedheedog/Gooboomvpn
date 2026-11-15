# BUST THE GATE v1.0.0

First public release of the ads-free Android OpenVPN connector for VPN Gate volunteer relays.

## Included

- VPN Gate relay discovery and refresh.
- Multiple VPN Gate-compatible CSV sources and imported OpenVPN profiles.
- Country filtering, manual relay selection, and relay sorting.
- Latency-first automatic selection and bounded failover attempts.
- Underlying-network change recovery.
- Connected-session timer and upload/download traffic totals.
- Embedded OpenVPN 3 core for Android.

## Downloads

- **Release APK**: recommended for normal installation; signed with the project's release key.
- **Debug APK**: intended for development and troubleshooting.
- **SHA256SUMS.txt**: checksums for both APKs.

## Compatibility

- Android 8.0 (API 26) or newer.
- `arm64-v8a` and `x86_64`.

## Important limitations

Live measured relay latency/throughput probing is not yet implemented; relay sorting uses published metadata. Failover and Wi-Fi/mobile network recovery have not been exhaustively validated across devices. VPN Gate servers are volunteer-operated and should not be considered trusted for sensitive traffic. Prefer HTTPS and other end-to-end encryption.

The app's core tunnel connection has been confirmed on a physical Android device. This is not a guarantee that every relay or network configuration will work.
