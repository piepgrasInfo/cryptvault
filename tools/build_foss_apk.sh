#!/usr/bin/env bash
# Builds the Play-services-free APK — the one anyone can install — and prints what has to be
# published with it (BUILD_BRIEF.md §13, decided 2026-09-19).
#
# Three channels take the same file: F-Droid, GitHub Releases and piepgras.info. The APK is
# renamed to carry its version, because a download folder full of cryptvault-foss-release.apk
# tells nobody anything.
#
#   tools/build_foss_apk.sh              # build, rename, hash
#   tools/build_foss_apk.sh --clean      # from scratch, for a release
#
# Signing: with keystore.properties present the APK is signed with the developer's key, which is
# what GitHub Releases and the website publish. F-Droid builds this same source itself and signs
# with its own key; if the developer wants one signature across all three channels, F-Droid's
# reproducible-build route is the way (they build, compare against this APK, and publish this
# one) — see README.md, "Variants".
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/build/outputs/apk/foss/release"

cd "$ROOT"
if [ "${1:-}" = "--clean" ]; then
  ./gradlew :app:clean --console=plain
fi
./gradlew :app:assembleFossRelease --console=plain

APK="$(ls -1 "$OUT"/cryptvault-foss-release*.apk 2>/dev/null | head -1 || true)"
if [ -z "$APK" ]; then
  echo "no APK in $OUT — did assembleFossRelease change its output name?" >&2; exit 1
fi

# The version the app itself reports, read back out of the build rather than typed twice.
VERSION="$(grep -oP '(?<=versionName = ")[^"]+' "$ROOT/app/build.gradle.kts" | head -1)"
CODE="$(grep -oP '(?<=versionCode = )[0-9]+' "$ROOT/app/build.gradle.kts" | head -1)"
case "$APK" in
  *unsigned*) SIGNED="UNSIGNED (no keystore.properties — do not publish this file)" ;;
  *)          SIGNED="signed with the key in keystore.properties" ;;
esac

RELEASE="$ROOT/app/build/outputs/cryptvault-$VERSION-foss.apk"
mkdir -p "$(dirname "$RELEASE")"
cp -f "$APK" "$RELEASE"

echo
echo "APK       $RELEASE"
echo "version   $VERSION (versionCode $CODE)"
echo "signing   $SIGNED"
echo "sha256    $(sha256sum "$RELEASE" | cut -d' ' -f1)"
echo
echo "Publish the SHA-256 next to the download on both channels, so the file can be checked"
echo "against the source in github/ (GPL source availability, README.md)."
