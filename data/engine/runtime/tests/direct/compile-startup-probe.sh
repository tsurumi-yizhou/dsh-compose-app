#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${1:?output jar path required}"
RUNTIME="$REPO/engine/src/main/kotlin/dev/fughetta/dshcompose/engine/runtime"
mkdir -p "$(dirname "$OUT")"
"$KOTLINC" "$RUNTIME/EngineProcess.kt" "$RUNTIME/RuntimeLaunch.kt" "$RUNTIME/ProotTempFiles.kt" "$RUNTIME/LaunchDiagnostics.kt" "$RUNTIME/DirectLaunch.kt" "$RUNTIME/L2sMigration.kt" "$RUNTIME/RuntimeProcessOwnership.kt" \
  "$HERE/cleanup-probe/AndroidContext.kt" "$HERE/cleanup-probe/AndroidOs.kt" \
  "$HERE/cleanup-probe/AndroidProcess.kt" \
  "$HERE/startup-probe/"*.kt -include-runtime -d "$OUT"
