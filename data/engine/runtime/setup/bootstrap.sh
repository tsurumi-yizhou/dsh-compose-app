#!/bin/sh
# Runs inside Ubuntu, with no terminal interaction required.
set -eu
export DEBIAN_FRONTEND=noninteractive
export TZ=Etc/UTC
# Android mounts supply /dev and /proc; package scripts must not start daemons.
mkdir -p /usr/sbin
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
printf 'DSH_SETUP tools\n'
if [ ! -f /opt/dsh-compose/tools-installed ]; then
    dpkg --configure -a
    apt-get update
    apt-get install -y --no-install-recommends ca-certificates curl xz-utils git python3 python3-pip tzdata
    apt-get clean
    touch /opt/dsh-compose/tools-installed
fi
printf 'DSH_SETUP node\n'
case "$DSH_SETUP_ABI" in
    arm64-v8a) arch=arm64 ;;
    x86_64) arch=x64 ;;
    *) exit 2 ;;
esac
if [ ! -x /opt/node/bin/node ] || [ "$(/opt/node/bin/node --version)" != "v$DSH_SETUP_NODE_VERSION" ]; then
    archive="node-v$DSH_SETUP_NODE_VERSION-linux-$arch.tar.xz"
    curl --fail --location --retry 3 --connect-timeout 30 \
        "https://nodejs.org/dist/v$DSH_SETUP_NODE_VERSION/$archive" -o /tmp/dsh-node.tar.xz
    rm -rf /opt/node-install
    mkdir -p /opt/node-install
    tar -xJf /tmp/dsh-node.tar.xz -C /opt/node-install --strip-components=1
    # Preserve the app's npm registry settings written into the base environment.
    mkdir -p /opt/node-install/etc
    cp /opt/node/etc/npmrc /opt/node-install/etc/npmrc
    rm -rf /opt/node
    mv /opt/node-install /opt/node
    rm -f /tmp/dsh-node.tar.xz
fi
printf 'DSH_SETUP pnpm\n'
pnpm_version="$(node -p "require('/tmp/dsh-engine-setup/pnpm/package.json').dependencies.pnpm")"
if [ ! -f /usr/local/lib/node_modules/pnpm/package.json ] || \
    [ "$(node -p "require('/usr/local/lib/node_modules/pnpm/package.json').version")" != "$pnpm_version" ]; then
    mkdir -p /usr/local/lib
    cp /tmp/dsh-engine-setup/pnpm/package.json /tmp/dsh-engine-setup/pnpm/package-lock.json /usr/local/lib/
    npm ci --prefix /usr/local/lib --ignore-scripts --no-audit --no-fund --cache /tmp/dsh-npm-cache
fi
