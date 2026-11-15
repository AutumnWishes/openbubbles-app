#!/usr/bin/env bash
set -euo pipefail

# A separate paired Google Messages test application, with no Apple/Rust setup.
# This build never signs into Google or sends a carrier message.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS_DIR="$ROOT_DIR/tools/rcs_bridge_harness"
UPSTREAM_DIR="$HARNESS_DIR/build/upstream"
ARTIFACT_DIR="$ROOT_DIR/build/rcs_bridge_harness/artifacts"
GO_COMMAND="${GO_BIN:-go}"
GRADLE_COMMAND="${GRADLE_BIN:-gradle}"
MODE="${1:-apk}"
UPSTREAM_SHA='b826f892d87274d465fb5b903870a831c12714ad'
MOBILE_VERSION='v0.0.0-20261011034528-197770444867'
RCS_BUILD_NUMBER="${RCS_BUILD_NUMBER:-4007}"
export GOTOOLCHAIN=local

case "$MODE" in go-test|bind|apk) ;; *) echo "Usage: $0 [go-test|bind|apk]" >&2; exit 2 ;; esac
if [[ ! "$RCS_BUILD_NUMBER" =~ ^[1-9][0-9]{0,7}$ ]]; then
  echo 'RCS_BUILD_NUMBER must be a positive integer of at most eight digits.' >&2
  exit 2
fi
export RCS_BUILD_NUMBER
if [[ "$MODE" != go-test ]]; then
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  if ! command -v javac >/dev/null || [[ ! "$(javac -version 2>&1)" =~ ^javac\ 21([.]|$) ]]; then
    echo 'A full JDK 21 is required (including javac). Set JAVA_HOME to its directory.' >&2
    exit 1
  fi
fi
if [[ "$("$GO_COMMAND" env GOVERSION)" != 'go1.27.2' ]]; then
  echo 'This harness is pinned to Go 1.27.2. Set GO_BIN to that compiler.' >&2
  exit 1
fi
# gomobile invokes the go command itself, so make the selected compiler visible.
export PATH="$(dirname "$(command -v "$GO_COMMAND")"):$PATH"

mkdir -p "$HARNESS_DIR/build"
if [[ -L "$UPSTREAM_DIR" ]]; then
  echo 'Refusing a symlink at the generated upstream source directory.' >&2
  exit 1
fi
if [[ ! -e "$UPSTREAM_DIR" ]]; then
  git clone --no-checkout --filter=blob:none https://github.com/mautrix/gmessages.git "$UPSTREAM_DIR"
  git -C "$UPSTREAM_DIR" checkout --detach "$UPSTREAM_SHA"
fi
if [[ "$(git -C "$UPSTREAM_DIR" rev-parse HEAD)" != "$UPSTREAM_SHA" ]]; then
  echo 'Generated upstream source does not match the pinned commit; use a fresh build directory.' >&2
  exit 1
fi
for PATCH_FILE in "$HARNESS_DIR"/bridge/patches/*.patch; do
  if git -C "$UPSTREAM_DIR" apply --check "$PATCH_FILE" 2>/dev/null; then
    git -C "$UPSTREAM_DIR" apply "$PATCH_FILE"
  elif ! git -C "$UPSTREAM_DIR" apply --reverse --check "$PATCH_FILE" 2>/dev/null; then
    echo "Cannot apply Google Messages safety patch: $(basename "$PATCH_FILE")" >&2
    exit 1
  fi
done

cd "$UPSTREAM_DIR"
"$GO_COMMAND" test -race ./pkg/libgm -run '^TestHarness'

cd "$HARNESS_DIR/bridge"
"$GO_COMMAND" mod download
"$GO_COMMAND" mod verify
"$GO_COMMAND" test -race ./...
"$GO_COMMAND" vet ./...
python3 -m unittest discover -s "$HARNESS_DIR/auth" -p 'test_*.py'
python3 -m unittest discover -s "$HARNESS_DIR" -p 'test_bundle_source.py'
if [[ "$MODE" == go-test ]]; then
  exit 0
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_HOME" ]]; then
  echo 'Set ANDROID_HOME to an Android SDK with API 36 and NDK 27.2.12479018.' >&2
  exit 1
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "Missing pinned Android NDK: $ANDROID_NDK_HOME" >&2
  exit 1
fi
export GOBIN="$HARNESS_DIR/build/bin"
mkdir -p "$GOBIN" "$HARNESS_DIR/android/app/libs"
export PATH="$GOBIN:$PATH"
"$GO_COMMAND" install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
"$GO_COMMAND" install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
# gomobile init installs gobind@latest. Its other required bind initialization
# is this work directory; install both tools at the pin above instead.
mkdir -p "$("$GO_COMMAND" env GOPATH)/pkg/gomobile"
gomobile bind -target=android/arm64 -androidapi=24 -javapkg=app.openbubbles.rcs \
  -trimpath -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' \
  -o "$HARNESS_DIR/android/app/libs/rcsbridge.aar" .
if [[ "$MODE" == bind ]]; then
  exit 0
fi

"$GRADLE_COMMAND" --no-daemon -p "$HARNESS_DIR/android" \
  :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
mkdir -p "$ARTIFACT_DIR"
cp "$HARNESS_DIR/android/app/build/outputs/apk/release/app-release.apk" "$ARTIFACT_DIR/RCSBridgeTest.apk"
python3 "$ROOT_DIR/tools/full_app_build/verify_apk.py" "$ARTIFACT_DIR/RCSBridgeTest.apk" \
  --expected-package app.openbubbles.rcsprototype --output "$ARTIFACT_DIR/APK-CHECKS.txt"
SOURCE_COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf 'source-archive')"
{
  printf 'Source commit: %s\n' "$SOURCE_COMMIT"
  printf 'Google Messages client commit: %s\n' "$UPSTREAM_SHA"
  printf 'Gomobile: %s\n' "$MOBILE_VERSION"
  printf 'Build number: %s\n' "$RCS_BUILD_NUMBER"
  printf 'Package: app.openbubbles.rcsprototype\n'
  printf 'Go: 1.27.2; Java: 21; Gradle: 8.9; Android SDK: 36; NDK: 27.2.12479018\n'
  printf 'Experimental companion app, signed with a generated Android test key.\n'
  printf 'No account sign-in or carrier RCS send was performed by CI.\n'
  printf 'Phone pairing, both SIMs, RCS transport, and background behavior need device testing.\n'
} > "$ARTIFACT_DIR/BUILD.txt"
cp "$HARNESS_DIR/README.md" "$ARTIFACT_DIR/README.md"
python3 "$HARNESS_DIR/bundle_source.py" --root "$ROOT_DIR" --upstream "$UPSTREAM_DIR" \
  --output "$ARTIFACT_DIR/RCSBridgeTest-source.zip"
cd "$ARTIFACT_DIR"
sha256sum RCSBridgeTest.apk RCSBridgeTest-source.zip > SHA256SUMS.txt
