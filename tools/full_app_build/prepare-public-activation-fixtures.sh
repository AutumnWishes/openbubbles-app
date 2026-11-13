#!/usr/bin/env bash
set -euo pipefail

# Preserve the upstream public CI approach solely to compile an experimental
# APK. The checked-in legacy certificate has expired and is not evidence of
# working fresh Apple activation. No user's identity or credentials are used.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERT_DIR="$ROOT_DIR/rustpush/certs"
mkdir -p "$CERT_DIR/fairplay"
for name in \
  4056631661436364584235346952193 \
  4056631661436364584235346952194 \
  4056631661436364584235346952195 \
  4056631661436364584235346952196 \
  4056631661436364584235346952197 \
  4056631661436364584235346952198 \
  4056631661436364584235346952199 \
  4056631661436364584235346952200 \
  4056631661436364584235346952201 \
  4056631661436364584235346952208; do
  for extension in pem crt; do
    target="$CERT_DIR/fairplay/$name.$extension"
    source="$CERT_DIR/legacy-fairplay/fairplay.$extension"
    if [[ -f "$target" ]] && ! cmp -s "$target" "$source"; then
      echo "Refusing to overwrite non-fixture activation material: $target" >&2
      exit 1
    fi
    cp "$source" "$target"
  done
done
printf '%s\n' 'WARNING: Building with public legacy activation fixtures; fresh Apple activation is unverified.' >&2
