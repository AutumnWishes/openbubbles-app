#!/usr/bin/env bash
set -euo pipefail

# Build the entire OpenBubbles application as a separate experimental alpha.
# The public upstream activation keys below are build fixtures, not working
# Apple activation credentials. Never replace the user's production app.
export CI=true
export BOT=true
export FLUTTER_SUPPRESS_ANALYTICS=true
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLUTTER_COMMAND="${FLUTTER_BIN:-flutter}"
GRADLE_COMMAND="${GRADLE_BIN:-gradle}"
ALPHA_BUILD_NUMBER="${ALPHA_BUILD_NUMBER:-5000}"
if [[ ! "$ALPHA_BUILD_NUMBER" =~ ^[1-9][0-9]{0,7}$ ]]; then
  echo "ALPHA_BUILD_NUMBER must be a positive integer of at most eight digits." >&2
  exit 2
fi
cd "$ROOT_DIR"
python3 -m unittest discover -s "$ROOT_DIR/tools/full_app_build" -p 'test_*.py'

for path in rustpush/Cargo.toml rustpush/apple-private-apis/Cargo.toml telephony_plus/pubspec.yaml; do
  if [[ ! -f "$path" ]]; then
    echo "Missing submodule: $path. Initialize the pinned public submodules first." >&2
    exit 1
  fi
done

# The asset must exist for Flutter packaging, even when optional integrations
# are disabled. A developer's existing values are never overwritten.
if [[ ! -f .env ]]; then
  printf 'GIPHY_API_KEY=\nREPORT_ISSUE_WEBHOOK=\n' > .env
fi

"$ROOT_DIR/tools/full_app_build/prepare-public-activation-fixtures.sh"
# Build the same tested, pinned Google Messages bridge used by the companion.
# Gradle consumes this generated AAR directly; no account data or binary is committed.
"$ROOT_DIR/tools/rcs_bridge_harness/build.sh" bind
"$GRADLE_COMMAND" --no-daemon -p "$ROOT_DIR/tools/rcs_bridge_harness/android" \
  :app:testReleaseUnitTest :app:lintRelease
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check pub get
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check test --no-pub test
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check build apk \
  --flavor rcsalpha --release --no-pub --target-platform android-arm64 \
  --split-per-abi --no-tree-shake-icons --build-number "$ALPHA_BUILD_NUMBER"
"$GRADLE_COMMAND" --no-daemon -p "$ROOT_DIR/android" \
  -Ptarget-platform=android-arm64 :app:testRcsalphaReleaseUnitTest

APK_DIR="$ROOT_DIR/build/app/outputs/flutter-apk"
python3 "$ROOT_DIR/tools/full_app_build/verify_apk.py" \
  "$APK_DIR/app-arm64-v8a-rcsalpha-release.apk" \
  --expected-package com.bluebubbles.messaging.rcsalpha --output "$APK_DIR/APK-CHECKS.txt"
cd "$APK_DIR"
sha256sum app-arm64-v8a-rcsalpha-release.apk > SHA256SUMS.txt
{
  printf 'Source commit: %s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)"
  printf 'Build number: %s\n' "$ALPHA_BUILD_NUMBER"
  printf 'Package: com.bluebubbles.messaging.rcsalpha\n'
  printf 'Experimental full app; signed with a generated Android test key.\n'
  printf 'Public legacy activation fixtures were used to compile this APK.\n'
  printf 'A successful build does not verify fresh Apple/iMessage activation.\n'
  printf 'Install alongside official OpenBubbles and the earlier SMS Alpha; do not uninstall them.\n'
  printf 'The RCS companion pairing is in a different app sandbox and is not imported automatically.\n'
  printf 'CI-generated test signing keys are not retained between runs; verify certificates before updating.\n'
  printf 'No Google pairing, live SMS/RCS send or cross-transport device test was performed by CI.\n'
  rustup run stable rustc --version
} > BUILD.txt
