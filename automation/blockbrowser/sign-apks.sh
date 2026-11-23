#!/usr/bin/env bash
# Signs the APKs Gradle built and copies them to an output folder as block-browser-<abi>.apk.
#
# Usage: sign-apks.sh <folder with Gradle's APKs> <output folder>
#
# With the repository secrets SIGNING_KEY (base64 of a .jks/.keystore file), KEY_ALIAS, KEY_STORE_PASSWORD and
# KEY_PASSWORD set, the APKs are signed with that stable key, so each build installs over the previous one.
# Without them, a throwaway key is generated for this build only: those APKs work, but can't update an install
# signed with a different key. <output folder>/SIGNING.txt says which happened (it goes into the release notes).
set -euo pipefail

IN="$1"
OUT="$2"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk/android-sdk-linux}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"
[ -x "$APKSIGNER" ] || { echo "apksigner not found under $SDK/build-tools" >&2; exit 1; }
ZIPALIGN="$(dirname "$APKSIGNER")/zipalign"

if [ -n "${SIGNING_KEY:-}" ]; then
  echo "$SIGNING_KEY" | base64 -d > "$WORK/key.jks"
  ALIAS="$KEY_ALIAS"
  STOREPASS="$KEY_STORE_PASSWORD"
  KEYPASS="${KEY_PASSWORD:-$KEY_STORE_PASSWORD}"
  echo "Signed with the Block Browser release key: installs over earlier builds signed with the same key." > "$OUT/SIGNING.txt"
else
  echo "::warning::SIGNING_KEY secret not set; signing with a throwaway key. See README → Signing."
  ALIAS=throwaway
  STOREPASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
  KEYPASS="$STOREPASS"
  keytool -genkeypair -keystore "$WORK/key.jks" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass "$STOREPASS" -keypass "$KEYPASS" -dname "CN=Block Browser throwaway" >/dev/null
  echo "Signed with a throwaway key (no signing secrets configured): uninstall any other Block Browser build before installing this one." > "$OUT/SIGNING.txt"
fi

count=0
for apk in "$IN"/*.apk; do
  name="$(basename "$apk")"
  abi="$(echo "$name" | grep -oE 'arm64-v8a|armeabi-v7a|x86_64|x86' | head -1 || true)"
  [ -n "$abi" ] || abi=universal
  # apksigner needs an aligned APK; native libraries need 16 KB alignment for Android 15+ devices.
  if "$ZIPALIGN" -c -P 16 4 "$apk" >/dev/null 2>&1; then
    src="$apk"
  else
    src="$WORK/aligned.apk"
    "$ZIPALIGN" -f -P 16 4 "$apk" "$src"
  fi
  "$APKSIGNER" sign --ks "$WORK/key.jks" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
    --out "$OUT/block-browser-$abi.apk" "$src"
  "$APKSIGNER" verify "$OUT/block-browser-$abi.apk"
  count=$((count + 1))
done
[ "$count" -gt 0 ] || { echo "No APKs found in $IN" >&2; exit 1; }
rm -f "$OUT"/*.idsig
ls -l "$OUT"
