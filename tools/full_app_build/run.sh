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
ALPHA_BUILD_NUMBER="${ALPHA_BUILD_NUMBER:-3000}"
if [[ ! "$ALPHA_BUILD_NUMBER" =~ ^[1-9][0-9]{0,7}$ ]]; then
  echo "ALPHA_BUILD_NUMBER must be a positive integer of at most eight digits." >&2
  exit 2
fi
cd "$ROOT_DIR"

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
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check pub get
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check test --no-pub test/services/native_sms
"$FLUTTER_COMMAND" --suppress-analytics --no-version-check build apk \
  --flavor alpha --release --no-pub --target-platform android-arm64 \
  --split-per-abi --no-tree-shake-icons --build-number "$ALPHA_BUILD_NUMBER"

APK_DIR="$ROOT_DIR/build/app/outputs/flutter-apk"
cd "$APK_DIR"
sha256sum app-alpha-arm64-v8a-release.apk > SHA256SUMS.txt
{
  printf 'Source commit: %s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)"
  printf 'Build number: %s\n' "$ALPHA_BUILD_NUMBER"
  printf 'Package: com.bluebubbles.messaging.alpha\n'
  printf 'Experimental full app; signed with a generated Android test key.\n'
  printf 'Public legacy activation fixtures were used to compile this APK.\n'
  printf 'A successful build does not verify fresh Apple/iMessage activation.\n'
  printf 'Install alongside the official OpenBubbles app; do not uninstall it.\n'
  rustup run stable rustc --version
} > BUILD.txt
