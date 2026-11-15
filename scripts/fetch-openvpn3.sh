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
# The pinned Android build uses OpenVPN's mbedTLS backend. mbedtls_cipher_setup()
# requires a context initialized by mbedtls_cipher_init(); upstream's wrapper
# currently omits that initialization. Patch the fetched source reproducibly.
python3 - "${DEST}/openvpn/mbedtls/crypto/cipher.hpp" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
old_ctor = "    CipherContextCommon() = default;\n"
new_ctor = """    CipherContextCommon()
    {
        mbedtls_cipher_init(&ctx);
    }
"""
old_init = """        erase();

        check_mode(mode);
"""
new_init = """        erase();

        // mbedtls_cipher_free() clears a previously used context; initialize it
        // again before setup, as required by the mbedTLS cipher API.
        mbedtls_cipher_init(&ctx);
        check_mode(mode);
"""
if old_ctor in source:
    source = source.replace(old_ctor, new_ctor, 1)
elif new_ctor not in source:
    raise SystemExit("Unexpected OpenVPN mbedTLS cipher wrapper constructor; refusing to patch")
if old_init in source:
    source = source.replace(old_init, new_init, 1)
elif new_init not in source:
    raise SystemExit("Unexpected OpenVPN mbedTLS cipher init path; refusing to patch")
path.write_text(source)
print("Applied explicit mbedTLS cipher-context initialization patch.")
PY

# Configure the padding callbacks required by mbedTLS 3.x for OpenVPN CBC.
# OpenVPN's data-channel CBC framing uses PKCS#7 padding; mbedTLS 3.x does not
# select a padding handler automatically after mbedtls_cipher_setup().
python3 - "${DEST}/openvpn/mbedtls/crypto/cipher.hpp" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
old = """        if (mbedtls_cipher_setup(&ctx, ci) < 0)
            throw mbedtls_cipher_error("mbedtls_cipher_setup");
"""
new = """        if (mbedtls_cipher_setup(&ctx, ci) < 0)
            throw mbedtls_cipher_error("mbedtls_cipher_setup");

#if defined(MBEDTLS_CIPHER_MODE_WITH_PADDING)
        // Configure PKCS#7 only for CBC. AES-CTR is also supported by this
        // backend and must retain its unpadded stream-style behavior.
        if (mbedtls_cipher_get_cipher_mode(&ctx) == MBEDTLS_MODE_CBC &&
            mbedtls_cipher_set_padding_mode(&ctx, MBEDTLS_PADDING_PKCS7) < 0)
            throw mbedtls_cipher_error("mbedtls_cipher_set_padding_mode(PKCS7)");
#endif
"""
if new not in source:
    if old not in source:
        raise SystemExit("Unexpected OpenVPN mbedTLS setup block; refusing padding patch")
    source = source.replace(old, new, 1)
    path.write_text(source)
    print("Applied CBC-only PKCS#7 padding configuration.")
else:
    print("CBC-only PKCS#7 padding configuration already present.")
PY

echo "Pinned source fetch verified. The Android adapter still requires a successful native build and real-device connection test."
