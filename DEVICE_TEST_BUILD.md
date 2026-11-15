# Device-test APK snapshot

Source baseline: 4f6624b58a95e6cdbedea193381e6ead5cdbcef4

This branch is a one-time device-testing snapshot of the default branch. The
marker commit triggers the separate device-test workflow to package and verify
both debug and release APK variants. The debug APK is intended for sideloaded
device testing; the release APK is expected to remain unsigned because no
release signing key is configured or requested.
