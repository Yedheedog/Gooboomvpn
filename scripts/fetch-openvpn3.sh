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


# Add narrowly scoped receive-path diagnostics to the pinned OpenVPN 3 source.
# This is intentionally applied after the mbedTLS context initialization patch.
python3 - "${DEST}" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
hmac_path = root / "openvpn/crypto/decrypt_chm.hpp"
cipher_path = root / "openvpn/mbedtls/crypto/cipher.hpp"

hmac_source = hmac_path.read_text()
cipher_source = cipher_path.read_text()

hmac_include = "#include <openvpn/log/sessionstats.hpp>\n"
hmac_include_new = hmac_include + "#include <atomic>\n"
if hmac_include_new not in hmac_source:
    if hmac_include not in hmac_source:
        raise SystemExit("Unexpected decrypt_chm.hpp include block; refusing diagnostic patch")
    hmac_source = hmac_source.replace(hmac_include, hmac_include_new, 1)

worker_anchor = """        // skip null packets
        if (buf.empty())
            return Error::SUCCESS;
"""
worker_replacement = """        // skip null packets
        if (buf.empty())
            return Error::SUCCESS;

        // Verify that this packet-processing thread inherited the OpenVPN log context.
        static std::atomic_flag diag_worker_marker_emitted = ATOMIC_FLAG_INIT;
        if (!diag_worker_marker_emitted.test_and_set(std::memory_order_relaxed))
            OPENVPN_LOG("DIAG_WORKER_TEST: OpenVPN data-channel worker logging context reached decrypt().");
"""

hmac_anchor = """        // verify the HMAC
        if (hmac.defined())
        {
            unsigned char local_hmac[CRYPTO_API::HMACContext::MAX_HMAC_SIZE];
            const size_t hmac_size = hmac.output_size();
            const unsigned char *packet_hmac = buf.read_alloc(hmac_size);
            hmac.hmac(local_hmac, hmac_size, buf.c_data(), buf.size());
            if (crypto::memneq(local_hmac, packet_hmac, hmac_size))
            {
                buf.reset_size();
                return Error::HMAC_ERROR;
            }
        }
"""
hmac_replacement = """        // verify the HMAC
        const size_t diag_original_packet_len = buf.size();
        const bool diag_hmac_configured = hmac.defined();
        if (diag_hmac_configured)
        {
            unsigned char local_hmac[CRYPTO_API::HMACContext::MAX_HMAC_SIZE];
            const size_t hmac_size = hmac.output_size();
            const unsigned char *packet_hmac = buf.read_alloc(hmac_size);
            hmac.hmac(local_hmac, hmac_size, buf.c_data(), buf.size());
            if (crypto::memneq(local_hmac, packet_hmac, hmac_size))
            {
                OPENVPN_LOG(std::string("DIAG_CHM: HMAC validation outcome=FAILURE | original_packet_len=") + std::to_string(diag_original_packet_len) + " hmac_size=" + std::to_string(hmac_size));
                buf.reset_size();
                return Error::HMAC_ERROR;
            }
            static std::atomic<unsigned int> diag_hmac_success_logs{0};
            if (diag_hmac_success_logs.fetch_add(1, std::memory_order_relaxed) < 3)
                OPENVPN_LOG(std::string("DIAG_CHM: HMAC validation outcome=SUCCESS | original_packet_len=") + std::to_string(diag_original_packet_len) + " hmac_size=" + std::to_string(hmac_size));
        }
        else
        {
            static std::atomic<unsigned int> diag_hmac_skipped_logs{0};
            if (diag_hmac_skipped_logs.fetch_add(1, std::memory_order_relaxed) < 3)
                OPENVPN_LOG(std::string("DIAG_CHM: HMAC validation outcome=SKIPPED | original_packet_len=") + std::to_string(diag_original_packet_len));
        }
"""
if hmac_replacement not in hmac_source:
    if hmac_anchor not in hmac_source:
        raise SystemExit("Unexpected decrypt_chm.hpp HMAC block; refusing diagnostic patch")
    hmac_source = hmac_source.replace(hmac_anchor, hmac_replacement, 1)

if worker_replacement not in hmac_source:
    if worker_anchor not in hmac_source:
        raise SystemExit("Unexpected decrypt_chm.hpp decrypt entry; refusing worker marker patch")
    hmac_source = hmac_source.replace(worker_anchor, worker_replacement, 1)

old_update = """        size_t outlen;
        if (mbedtls_cipher_update(&ctx, in, in_size, out, &outlen) >= 0)
        {
            out_acc += outlen;
            return true;
        }
        else
            return false;
"""
new_update = """        size_t outlen;
        const int diag_ret = mbedtls_cipher_update(&ctx, in, in_size, out, &outlen);
        if (diag_ret >= 0)
        {
            out_acc += outlen;
            return true;
        }
        OPENVPN_LOG(std::string("DIAG_CIPHER: mbedtls_cipher_update failure | ret=") + std::to_string(diag_ret) + " in_size=" + std::to_string(in_size));
        return false;
"""
if new_update not in cipher_source:
    if old_update not in cipher_source:
        raise SystemExit("Unexpected mbedTLS cipher update block; refusing diagnostic patch")
    cipher_source = cipher_source.replace(old_update, new_update, 1)

old_final = """        size_t outlen;
        if (mbedtls_cipher_finish(&ctx, out, &outlen) >= 0)
        {
            out_acc += outlen;
            return true;
        }
        else
            return false;
"""
new_final = """        size_t outlen;
        const int diag_ret = mbedtls_cipher_finish(&ctx, out, &outlen);
        if (diag_ret >= 0)
        {
            out_acc += outlen;
            return true;
        }
        OPENVPN_LOG(std::string("DIAG_CIPHER: mbedtls_cipher_finish failure | ret=") + std::to_string(diag_ret));
        return false;
"""
if new_final not in cipher_source:
    if old_final not in cipher_source:
        raise SystemExit("Unexpected mbedTLS cipher finish block; refusing diagnostic patch")
    cipher_source = cipher_source.replace(old_final, new_final, 1)

# Record the exact packet dimensions at the existing DECRYPT_ERROR decision.
# The HMAC gate above has already succeeded (or is not configured) at this point.
decrypt_anchor = """            // decrypt from buf -> work
            const size_t decrypt_bytes = cipher.decrypt(iv_buf, work.data(), work.max_size(), buf.c_data(), buf.size());
            if (!decrypt_bytes)
            {
                buf.reset_size();
                return Error::DECRYPT_ERROR;
            }
"""
decrypt_replacement = """            // decrypt from buf -> work
            const size_t diag_ciphertext_len = buf.size();
            const size_t diag_iv_len = iv_length;
            const size_t decrypt_bytes = cipher.decrypt(iv_buf, work.data(), work.max_size(), buf.c_data(), buf.size());
            if (!decrypt_bytes)
            {
                OPENVPN_LOG(std::string("DIAG_CHM_FATAL: decrypt_bytes evaluated to ZERO")
                            + " | original_packet_len=" + std::to_string(diag_original_packet_len)
                            + " | ciphertext_len=" + std::to_string(diag_ciphertext_len)
                            + " | iv_len=" + std::to_string(diag_iv_len)
                            + " | hmac_configured=" + (diag_hmac_configured ? "true" : "false")
                            + " | hmac_gate=" + (diag_hmac_configured ? "passed" : "not-configured")
                            + " | decrypt_bytes=" + std::to_string(decrypt_bytes));
                buf.reset_size();
                return Error::DECRYPT_ERROR;
            }
"""
if decrypt_replacement not in hmac_source:
    if decrypt_anchor not in hmac_source:
        raise SystemExit("Unexpected decrypt_chm.hpp cipher-decrypt block; refusing fatal diagnostic patch")
    hmac_source = hmac_source.replace(decrypt_anchor, decrypt_replacement, 1)

hmac_path.write_text(hmac_source)
cipher_path.write_text(cipher_source)
print("Applied verified, bounded OpenVPN receive-path diagnostics.")
PY

echo "Pinned source fetch verified. The Android adapter still requires a successful native build and real-device connection test."
