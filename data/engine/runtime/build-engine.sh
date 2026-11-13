#!/usr/bin/env bash
# Package a released host with Android guest installation scripts.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$1"
VERSION="$2"
HOST_PACKAGE="$3"
WORK="$HERE/.work/engine-package"
mkdir -p "$WORK" "$OUT/runtime"
STAGE=$(mktemp -d "$WORK/stage.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/host" "$STAGE/package/pnpm"
tar -xzf "$HOST_PACKAGE" -C "$STAGE/host"
cp -a "$STAGE/host/overrides" "$STAGE/host/locks" "$STAGE/host/bundles" "$STAGE/package/"
mv "$STAGE/package/bundles/dsh-compose-bridge" "$STAGE/package/bridge"
cp "$HERE/locks/pnpm-12.18.0/package.json" "$HERE/locks/pnpm-12.18.0/package-lock.json" "$STAGE/package/pnpm/"
cp "$HERE/setup/install.sh" "$HERE/setup/bootstrap.sh" "$HERE/setup/finalize.mjs" "$STAGE/package/"
cp "$STAGE/host/LICENSE.md" "$STAGE/package/"
# The plugin authoring skill is owned and shipped by the host.
rm -rf "$OUT/skills"
cp -a "$STAGE/host/skills" "$OUT/skills"
tar -czf "$OUT/runtime/engine.tgz" -C "$STAGE/package" .
printf '%s\n' "$VERSION" > "$OUT/runtime/engine-version.txt"
