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

# Instrument the exact CBC/HMAC receive path without exposing secrets. The
# counters bound normal diagnostic output; failures are always emitted.
python3 - "${DEST}/openvpn/mbedtls/crypto/cipher.hpp" "${DEST}/openvpn/crypto/decrypt_chm.hpp" <<'PY'
from pathlib import Path
import sys

cipher_path = Path(sys.argv[1])
decrypt_path = Path(sys.argv[2])
cipher = cipher_path.read_text()
decrypt = decrypt_path.read_text()
marker = "VPNGATE_DIAG"

def patch_file(path, source, replacements, required_markers):
    if marker in source:
        if not all(required in source for required in required_markers):
            raise SystemExit(f"Partial diagnostic patch detected in {path}; refusing to continue")
        print(f"Diagnostic instrumentation already present in {path}")
        return source
    for old, new, label in replacements:
        count = source.count(old)
        if count != 1:
            raise SystemExit(f"Expected exactly one anchor for {label} in {path}; found {count}")
        source = source.replace(old, new, 1)
    path.write_text(source)
    print(f"Applied diagnostic instrumentation to {path}")
    return source

cipher_replacements = [
(
    '#include <string>\n\n#include <mbedtls/cipher.h>\n',
    '#include <string>\n#include <atomic>\n\n#include <mbedtls/cipher.h>\n#include <openvpn/log/logbase.hpp>\n',
    '#include <openvpn/log/logbase.hpp>'
),
(
    '    bool initialized = false;\n    mbedtls_cipher_context_t ctx;\n',
    '    bool initialized = false;\n    const char *diagnostic_cipher_name = "unknown";\n    mbedtls_cipher_context_t ctx;\n',
    'diagnostic_cipher_name'
),
(
    '        erase();\n\n        check_mode(mode);\n',
    '        erase();\n        diagnostic_cipher_name = CryptoAlgs::name(alg);\n\n        check_mode(mode);\n',
    'cipher name capture'
),
(
    '''        size_t outlen;
        if (mbedtls_cipher_update(&ctx, in, in_size, out, &outlen) >= 0)
        {
            out_acc += outlen;
            return true;
        }
        else
            return false;
''',
    '''        size_t outlen = 0;
        const int ret = mbedtls_cipher_update(&ctx, in, in_size, out, &outlen);
        static std::atomic<unsigned int> trace_count{0};
        const unsigned int trace_index = trace_count.fetch_add(1, std::memory_order_relaxed);
        if (trace_index < 16 || ret < 0)
            OPENVPN_LOG("VPNGATE_DIAG mbedtls_cipher_update algorithm=" << diagnostic_cipher_name
                        << " input_len=" << in_size << " return=" << ret << " output_len=" << outlen);
        if (ret >= 0)
        {
            out_acc += outlen;
            return true;
        }
        else
            return false;
''',
    'mbedtls_cipher_update trace'
),
(
    '''        size_t outlen;
        if (mbedtls_cipher_finish(&ctx, out, &outlen) >= 0)
        {
            out_acc += outlen;
            return true;
        }
        else
            return false;
''',
    '''        size_t outlen = 0;
        const int ret = mbedtls_cipher_finish(&ctx, out, &outlen);
        static std::atomic<unsigned int> trace_count{0};
        const unsigned int trace_index = trace_count.fetch_add(1, std::memory_order_relaxed);
        if (trace_index < 16 || ret < 0)
            OPENVPN_LOG("VPNGATE_DIAG mbedtls_cipher_finish algorithm=" << diagnostic_cipher_name
                        << " return=" << ret << " output_len=" << outlen);
        if (ret >= 0)
        {
            out_acc += outlen;
            return true;
        }
        else
            return false;
''',
    'mbedtls_cipher_finish trace'
)
]
decrypt_replacements = [
(
    '#include <cstring>\n',
    '#include <cstring>\n#include <atomic>\n\n#include <openvpn/log/logbase.hpp>\n',
    '#include <openvpn/log/logbase.hpp>'
),
(
    '''            hmac.hmac(local_hmac, hmac_size, buf.c_data(), buf.size());
            if (crypto::memneq(local_hmac, packet_hmac, hmac_size))
            {
                buf.reset_size();
                return Error::HMAC_ERROR;
            }
        }

        // decrypt packet ID + payload
''',
    '''            hmac.hmac(local_hmac, hmac_size, buf.c_data(), buf.size());
            const bool hmac_failed = crypto::memneq(local_hmac, packet_hmac, hmac_size) != 0;
            static std::atomic<unsigned int> hmac_trace_count{0};
            const unsigned int hmac_trace_index = hmac_trace_count.fetch_add(1, std::memory_order_relaxed);
            if (hmac_trace_index < 16 || hmac_failed)
                OPENVPN_LOG("VPNGATE_DIAG HMAC status=" << (hmac_failed ? "FAILURE" : "SUCCESS")
                            << " tag_len=" << hmac_size << " authenticated_len=" << buf.size());
            if (hmac_failed)
            {
                buf.reset_size();
                return Error::HMAC_ERROR;
            }
        }
        else
        {
            static std::atomic<unsigned int> hmac_skip_trace_count{0};
            if (hmac_skip_trace_count.fetch_add(1, std::memory_order_relaxed) < 16)
                OPENVPN_LOG("VPNGATE_DIAG HMAC status=SKIPPED");
        }

        // decrypt packet ID + payload
''',
    'HMAC verification trace'
)
]
cipher = patch_file(
    cipher_path, cipher, cipher_replacements,
    ["diagnostic_cipher_name", "mbedtls_cipher_update algorithm=", "mbedtls_cipher_finish algorithm="]
)
decrypt = patch_file(
    decrypt_path, decrypt, decrypt_replacements,
    ["HMAC status=", "HMAC status=SKIPPED"]
)
PY

echo "Pinned source fetch verified. The Android adapter still requires a successful native build and real-device connection test."
