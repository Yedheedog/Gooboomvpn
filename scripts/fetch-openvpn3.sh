#!/usr/bin/env bash
set -euo pipefail

# Reproducibly fetch the upstream OpenVPN 3 client core used by the Android JNI
# adapter. Fetching source alone is not evidence of a working VPN connection.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/app/src/main/cpp/third_party/openvpn3"
REV="0c1ce172fe918b05463d71e682a0c2f2d8fe7899"
URL="https://github.com/OpenVPN/openvpn3.git"

if ! command -v git >/dev/null 2>&1; then
  echo "git is required. On Termux run: pkg install git" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
if [ -d "$DEST/.git" ]; then
  git -C "$DEST" fetch --depth 1 origin "$REV"
  git -C "$DEST" checkout --detach "$REV"
elif [ -e "$DEST" ]; then
  echo "Refusing to overwrite existing non-git path: $DEST" >&2
  exit 1
else
  git clone --filter=blob:none --no-checkout "$URL" "$DEST"
  git -C "$DEST" fetch --depth 1 origin "$REV"
  git -C "$DEST" checkout --detach "$REV"
fi

ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
if [ "$ACTUAL" != "$REV" ]; then
  echo "OpenVPN 3 revision mismatch: expected $REV, got $ACTUAL" >&2
  exit 1
fi

echo "OpenVPN 3 source staged at:"
echo "  $DEST"
echo "  revision $ACTUAL"
echo
echo "Pinned source fetch verified. The Android adapter still requires a successful native build and real-device connection test."
