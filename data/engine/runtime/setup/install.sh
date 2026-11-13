#!/bin/sh
# Runs inside the extracted guest before the runtime is activated.
set -eu
PACKAGE=/tmp/dsh-engine-setup
STAGE=/opt/dsh-install
/bin/sh "$PACKAGE/bootstrap.sh"
printf 'DSH_SETUP engine\n'
trap 'rm -rf "$STAGE"' EXIT
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$PACKAGE/locks/package.json" "$PACKAGE/locks/package-lock.json" "$STAGE/"
# Use the guest's registry for the dependency graph shipped with this app.
node --input-type=module - "$STAGE/package-lock.json" <<'JS'
import { readFileSync, writeFileSync } from 'node:fs';
const file = process.argv[2];
const lock = JSON.parse(readFileSync(file, 'utf8'));
for (const entry of Object.values(lock.packages ?? {})) delete entry.resolved;
writeFileSync(file, JSON.stringify(lock));
JS
npm ci --prefix "$STAGE" --ignore-scripts --no-audit --no-fund --cache /tmp/dsh-npm-cache
cp -a "$PACKAGE/overrides/." "$STAGE/"
cp -a "$PACKAGE/bundles/." "$STAGE/node_modules/"
node "$PACKAGE/finalize.mjs" "$STAGE" "$PACKAGE/bundles"
rm -rf /opt/dsh
mv "$STAGE" /opt/dsh
mkdir -p /opt/dsh-compose
rm -rf /opt/dsh-compose/dsh-compose-bridge
cp -a "$PACKAGE/bridge" /opt/dsh-compose/dsh-compose-bridge
mv /opt/dsh/native-addons.json /opt/dsh-compose/direct/native-addons.json
rm -rf /tmp/dsh-npm-cache
