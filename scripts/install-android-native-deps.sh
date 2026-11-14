#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VCPKG_REV="69a1a33b4ba2b21e392c17e215ff637290778a97"
VCPKG_ROOT="${VCPKG_ROOT:-${ROOT}/.tools/vcpkg}"
NDK_VERSION="27.2.12479018"

if [ ! -d "${ROOT}/app/src/main/cpp/third_party/openvpn3/.git" ]; then
  echo "Run scripts/fetch-openvpn3.sh before installing native dependencies." >&2
  exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [ -n "${SDK_ROOT}" ] && [ -d "${SDK_ROOT}/ndk/${NDK_VERSION}" ]; then
    export ANDROID_NDK_HOME="${SDK_ROOT}/ndk/${NDK_VERSION}"
  fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]; then
  echo "ANDROID_NDK_HOME must point to Android NDK ${NDK_VERSION}." >&2
  exit 1
fi

if [ -e "${VCPKG_ROOT}" ] && [ ! -d "${VCPKG_ROOT}/.git" ]; then
  echo "Refusing to use non-git VCPKG_ROOT: ${VCPKG_ROOT}" >&2
  exit 1
fi
if [ ! -d "${VCPKG_ROOT}/.git" ]; then
  mkdir -p "$(dirname "${VCPKG_ROOT}")"
  git clone --filter=blob:none https://github.com/microsoft/vcpkg.git "${VCPKG_ROOT}"
fi

git -C "${VCPKG_ROOT}" fetch --depth 1 origin "${VCPKG_REV}"
git -C "${VCPKG_ROOT}" checkout --detach "${VCPKG_REV}"
ACTUAL="$(git -C "${VCPKG_ROOT}" rev-parse HEAD)"
if [ "${ACTUAL}" != "${VCPKG_REV}" ]; then
  echo "vcpkg revision mismatch: expected ${VCPKG_REV}, got ${ACTUAL}" >&2
  exit 1
fi

"${VCPKG_ROOT}/bootstrap-vcpkg.sh" -disableMetrics

# Keep each ABI in its own vcpkg manifest install root. A single shared
# install root is replaced when the next triplet is installed.
for triplet in arm64-android x64-android; do
  echo "Installing OpenVPN 3 dependencies for ${triplet}"
  "${VCPKG_ROOT}/vcpkg" install \
    --x-manifest-root="${ROOT}" \
    --triplet "${triplet}" \
    --x-install-root="${ROOT}/.native-deps/${triplet}" \
    --clean-after-build
  if [ ! -d "${ROOT}/.native-deps/${triplet}/${triplet}/include" ]; then
    echo "vcpkg did not create the expected ${triplet} include tree." >&2
    find "${ROOT}/.native-deps/${triplet}" -maxdepth 3 -type d -print >&2 || true
    exit 1
  fi
done

echo "Android native dependencies installed under ${ROOT}/.native-deps/<triplet>/<triplet>."
