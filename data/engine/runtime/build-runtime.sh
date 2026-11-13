#!/usr/bin/env bash
# Builds the bootstrap Linux environment for dsh-app (Ubuntu Base + Termux proot) for each Android ABI, entirely on an x86_64 Linux host without
# executing any foreign-architecture code.
#
#   data/engine/runtime/build-runtime.sh                 # both ABIs (x86_64 first)
#   data/engine/runtime/build-runtime.sh x86_64          # one ABI
#   COMPRESS=gz data/engine/runtime/build-runtime.sh     # .tar.gz instead of .tar.xz
#   RUNTIME_OUT=/path data/engine/runtime/build-runtime.sh   # stage outputs outside data/engine/runtime/out (not packaged)
#
# Engine packages are installed separately by app setup. This archive carries the base tools.
#
# Outputs (consumed by app/build.gradle.kts flavors):
#   data/engine/runtime/out/<abi>/assets/runtime/rootfs-<abi>.tar.xz
#   data/engine/runtime/out/<abi>/assets/runtime/manifest.json      (versions, hashes, launch template)
#   data/engine/runtime/out/<abi>/assets/runtime/licenses/...
#   data/engine/runtime/out/<abi>/jniLibs/<abi>/lib{proot,proot-loader,talloc,android-shmem}.so
#   data/engine/runtime/out/<abi>/build-report.json                  (ELF audit, pruned files, warnings)
#   data/engine/runtime/out/manifest.json                            (index of all built ABIs)
#
# Node, Python, git, pnpm and engine packages are installed inside proot by app setup.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$HERE/.cache"
WORK="$HERE/.work"
OUT="${RUNTIME_OUT:-$HERE/out}"   # the app build reads runtime/out; RUNTIME_OUT stages a build elsewhere
LOCKS="$HERE/locks"
PY=(python3 -I "$HERE/lib/rtbuild.py")

# ---- pinned inputs (override via environment; pinned hashes then must be updated too) ----
RUNTIME_REVISION="${RUNTIME_REVISION:-r15}"  # r14 + per-family DNS under PRoot, input recovery, session deletion and upload resolver fixes, title and boot cache work; DSH as r12
DSH_VERSION="${DSH_VERSION:-0.2.2-rc.3}"
NODE_VERSION="${NODE_VERSION:-26.13.1}"
# pnpm runs DSH plugin installs (pluginManager / `dsh plugin`).
PNPM_VERSION="${PNPM_VERSION:-12.18.0}"
# Use the package metadata from the same lockfile shipped to the device.
PNPM_INTEGRITY="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["packages"]["node_modules/pnpm"]["integrity"])' "$LOCKS/pnpm-$PNPM_VERSION/package-lock.json")"
UBUNTU_VERSION="${UBUNTU_VERSION:-24.04.5}"
PROOT_VERSION="5.1.107.102"
TALLOC_VERSION="2.5.0"
SHMEM_VERSION="0.7"
COMPRESS="${COMPRESS:-xz}"          # xz | gz
XZ_PRESET="${XZ_PRESET:-6}"         # 8 MiB dictionary: modest memory for xz-java on phones
XZ_THREADS="${XZ_THREADS:-4}"       # each xz -6 worker needs ~100 MiB; output does not depend on this
PRUNE_DEV_METADATA="${PRUNE_DEV_METADATA:-1}"   # drop *.map / *.d.ts from node_modules
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"  # 2026-01-01T00:00:00Z, fixed tar mtimes

UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# sha256 pins, cross-checked against the upstream SHA256SUMS / SHASUMS256.txt from
# cdimage.ubuntu.com / nodejs.org and the Termux Packages index.
declare -A PIN=(
  [ubuntu-base-24.04.5-base-arm64.tar.gz]=a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
  [ubuntu-base-24.04.5-base-amd64.tar.gz]=e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86
  [proot_5.1.107.102_aarch64.deb]=10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030
  [proot_5.1.107.102_x86_64.deb]=2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758
  [libtalloc_2.5.0_aarch64.deb]=556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
  [libtalloc_2.5.0_x86_64.deb]=b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530
  [libandroid-shmem_0.7_aarch64.deb]=0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
  [libandroid-shmem_0.7_x86_64.deb]=ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797
  # GPL-2.0 source of the proot binary (same archive Termux's build recipe pins); used for COPYING.
  [proot-v5.1.107.98.zip]=9de61bddd6f5016d065e2755b72ff4747290e5f992562f031785b107b51d02ba
)

log() { printf '\033[1;34m[runtime %s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { printf '\033[1;31m[runtime] error:\033[0m %s\n' "$*" >&2; exit 1; }
sha256() { sha256sum "$1" | cut -d' ' -f1; }

for tool in curl sha256sum python3 tar xz; do
  command -v "$tool" >/dev/null || die "missing host tool: $tool"
done

# fetch URL DEST [expected-sha256]: cached download with checksum verification.
fetch() {
  local url="$1" dest="$2" want="${3:-}"
  mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" && -n "$want" && "$(sha256 "$dest")" == "$want" ]]; then return 0; fi
  if [[ -f "$dest" && -z "$want" ]]; then return 0; fi
  log "download $url"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -sS -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
  if [[ -n "$want" ]]; then
    local got; got="$(sha256 "$dest")"
    [[ "$got" == "$want" ]] || { rm -f "$dest"; die "sha256 mismatch for $dest: got $got want $want"; }
  fi
}

pin_of() {
  local name="$1"
  if [[ -n "${PIN[$name]:-}" ]]; then printf '%s' "${PIN[$name]}"; fi
}

# ---------------------------------------------------------------------------
# inputs
# ---------------------------------------------------------------------------
ubuntu_base() {  # $1 = ubuntu arch; echoes path
  local arch="$1" name="ubuntu-base-${UBUNTU_VERSION}-base-$1.tar.gz"
  local dir="$CACHE/ubuntu/$UBUNTU_VERSION"
  fetch "$UBUNTU_BASE_URL/SHA256SUMS" "$dir/SHA256SUMS"
  local listed; listed="$(awk -v f="$name" '{n=$2; sub(/^\*/,"",n); if (n==f) print $1}' "$dir/SHA256SUMS")"
  [[ -n "$listed" ]] || { rm -f "$dir/SHA256SUMS"; die "$name not listed in SHA256SUMS (stale cache removed, rerun)"; }
  local pin; pin="$(pin_of "$name")"
  [[ -z "$pin" || "$pin" == "$listed" ]] || die "SHA256SUMS disagrees with pinned hash for $name"
  fetch "$UBUNTU_BASE_URL/$name" "$dir/$name" "$listed"
  printf '%s' "$dir/$name"
}

termux_deb() {  # $1 = package, $2 = version, $3 = termux arch; echoes path
  local pkg="$1" ver="$2" arch="$3" name="$1_$2_$3.deb"
  local dest="$CACHE/termux/$name" pin; pin="$(pin_of "$name")"
  if [[ -f "$dest" && -n "$pin" && "$(sha256 "$dest")" == "$pin" ]]; then printf '%s' "$dest"; return; fi
  # Resolve pool path + hash from the current Packages index (verifies index -> deb).
  local idx="$CACHE/termux/Packages-$arch"
  fetch "$TERMUX_REPO/dists/stable/main/binary-$arch/Packages" "$idx.new" && mv "$idx.new" "$idx"
  local rec; rec="$(awk -v RS= -v p="$pkg" -v v="$ver" \
    '$0 ~ "(^|\n)Package: " p "\n" && $0 ~ "\nVersion: " v "\n"' "$idx")"
  [[ -n "$rec" ]] || die "Termux index no longer has $pkg $ver for $arch (bump the pin in build-runtime.sh)"
  local file sum; file="$(sed -n 's/^Filename: //p' <<<"$rec")"; sum="$(sed -n 's/^SHA256: //p' <<<"$rec")"
  [[ -z "$pin" || "$pin" == "$sum" ]] || die "Termux index hash for $name differs from pin"
  fetch "$TERMUX_REPO/$file" "$dest" "$sum"
  printf '%s' "$dest"
}

# ---------------------------------------------------------------------------
# per-ABI build
# ---------------------------------------------------------------------------
build_abi() {
  local abi="$1" ubuntu_arch termux_arch elf_name
  case "$abi" in
    arm64-v8a) ubuntu_arch=arm64; termux_arch=aarch64; elf_name="ARM aarch64" ;;
    x86_64)    ubuntu_arch=amd64; node_arch=x64;   npm_cpu=x64;   termux_arch=x86_64;  elf_name="x86-64" ;;
    *) die "unknown ABI $abi (use arm64-v8a or x86_64)" ;;
  esac
  log "=== $abi ==="
  local work="$WORK/$abi" out="$OUT/$abi"
  local assets="$out/assets/runtime" jni="$out/jniLibs/$abi"
  mkdir -p "$work"
  rm -rf "$out"; mkdir -p "$assets/licenses" "$jni"

  local base proot_deb talloc_deb shmem_deb proot_src
  base="$(ubuntu_base "$ubuntu_arch")"
  proot_deb="$(termux_deb proot "$PROOT_VERSION" "$termux_arch")"
  talloc_deb="$(termux_deb libtalloc "$TALLOC_VERSION" "$termux_arch")"
  shmem_deb="$(termux_deb libandroid-shmem "$SHMEM_VERSION" "$termux_arch")"
  proot_src="$CACHE/termux/proot-v$PROOT_VERSION.zip"
  fetch "https://github.com/termux/proot/archive/v$PROOT_VERSION.zip" "$proot_src" "$(pin_of "proot-v$PROOT_VERSION.zip")"

  # -- jniLibs: Termux binaries, renamed lib*.so so the package manager extracts them --
  local debx="$work/debs"; rm -rf "$debx"
  local d
  for d in "$proot_deb" "$talloc_deb" "$shmem_deb"; do "${PY[@]}" deb-extract "$d" "$debx"; done
  local tprefix="$debx/data/data/com.termux/files/usr"
  cp "$tprefix/bin/proot"                  "$jni/libproot.so"
  cp "$tprefix/libexec/proot/loader"       "$jni/libproot-loader.so"
  cp "$tprefix/lib/libtalloc.so.$TALLOC_VERSION" "$jni/libtalloc.so"
  cp "$tprefix/lib/libandroid-shmem.so"    "$jni/libandroid-shmem.so"
  local orig_proot orig_talloc
  orig_proot="$(sha256 "$jni/libproot.so")"; orig_talloc="$(sha256 "$jni/libtalloc.so")"
  # APKs can only carry lib*.so, so the DT_NEEDED/SONAME "libtalloc.so.2" becomes "libtalloc.so".
  "${PY[@]}" patch-dynstr "$jni/libproot.so" libtalloc.so.2 libtalloc.so >&2
  "${PY[@]}" patch-dynstr "$jni/libtalloc.so" libtalloc.so.2 libtalloc.so >&2
  chmod 0755 "$jni"/*.so
  "${PY[@]}" elf-info "$jni"/*.so > "$work/jni-elf.json"

  python3 -I - "$jni" "$work/jni-elf.json" "$work/jnilibs.json" <<PYEOF
import hashlib, json, os, sys
jni, elf, out = sys.argv[1:4]
info = json.load(open(elf))
src = {
  "libproot.so": ("proot", "$PROOT_VERSION", "$(basename "$proot_deb")", "usr/bin/proot",
                  "DT_NEEDED libtalloc.so.2 -> libtalloc.so", "$orig_proot"),
  "libproot-loader.so": ("proot", "$PROOT_VERSION", "$(basename "$proot_deb")", "usr/libexec/proot/loader", None, None),
  "libtalloc.so": ("libtalloc", "$TALLOC_VERSION", "$(basename "$talloc_deb")", "usr/lib/libtalloc.so.$TALLOC_VERSION",
                   "DT_SONAME libtalloc.so.2 -> libtalloc.so", "$orig_talloc"),
  "libandroid-shmem.so": ("libandroid-shmem", "$SHMEM_VERSION", "$(basename "$shmem_deb")", "usr/lib/libandroid-shmem.so", None, None),
}
res = {}
for name, (pkg, ver, deb, inner, patch, orig) in src.items():
    path = os.path.join(jni, name)
    e = info[path]
    res[name] = {
        "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
        "bytes": os.path.getsize(path),
        "source": "termux-main %s %s (%s:/data/data/com.termux/files/%s)" % (pkg, ver, deb, inner),
        "patch": patch, "originalSha256": orig,
        "machine": e["machine"], "type": e["type"], "needed": e.get("needed"),
        "soname": e.get("soname"), "minLoadAlign": e["minLoadAlign"],
    }
json.dump(res, open(out, "w"), indent=2)
PYEOF
  # licenses for the native bits
  python3 -I - "$proot_src" "$assets/licenses/proot-COPYING.txt" <<'PYEOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
name = next(n for n in z.namelist() if n.endswith("/COPYING") and n.count("/") == 1)
open(sys.argv[2], "wb").write(z.read(name))
PYEOF
  cp "$debx/data/data/com.termux/files/usr/share/doc/libandroid-shmem/copyright" "$assets/licenses/libandroid-shmem-copyright.txt"
  sed -n '1,/\*\//p' "$tprefix/include/talloc.h" > "$assets/licenses/libtalloc-notice.txt"

  # -- runtimeVersion: hash of every input + the recipe itself --
  local inputs_sha
  inputs_sha="$( {
      echo "$RUNTIME_REVISION $abi $DSH_VERSION $NODE_VERSION $PNPM_VERSION $UBUNTU_VERSION $COMPRESS $XZ_PRESET $PRUNE_DEV_METADATA $SOURCE_DATE_EPOCH"
      sha256 "$base"
      sha256 "$proot_deb"; sha256 "$talloc_deb"; sha256 "$shmem_deb"
      sha256 "$HERE/build-runtime.sh"; sha256 "$HERE/lib/rtbuild.py"
      (cd "$HERE" && find boot direct -type d -name node_modules -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum)
    } | sha256sum | cut -c1-12 )"
  local runtime_version="${RUNTIME_REVISION}-dsh${DSH_VERSION}-node${NODE_VERSION}-ubuntu${UBUNTU_VERSION}-${inputs_sha}"
  local runtime_info
  runtime_info="$(printf '{"runtimeVersion":"%s","abi":"%s","dshVersion":"%s","nodeVersion":"%s","pnpmVersion":"%s","ubuntuVersion":"%s","prootVersion":"%s"}' \
    "$runtime_version" "$abi" "$DSH_VERSION" "$NODE_VERSION" "$PNPM_VERSION" "$UBUNTU_VERSION" "$PROOT_VERSION" \
    )"

  # -- rootfs archive --
  local archive="$assets/rootfs-$abi.tar.$COMPRESS"
  local -a prune=(); [[ "$PRUNE_DEV_METADATA" == 1 ]] && prune=(--prune-dev-metadata)
  log "assembling rootfs ($COMPRESS)"
  "${PY[@]}" assemble --abi "$abi" --base "$base" \
    --out "$archive" --report "$out/build-report.json" --runtime-info "$runtime_info" \
    --compress "$COMPRESS" --xz-preset "$XZ_PRESET" --threads "$XZ_THREADS" --epoch "$SOURCE_DATE_EPOCH" "${prune[@]}"

  local versions
  versions="$(printf '{"runtimeVersion":"%s","dshVersion":"%s","nodeVersion":"%s","pnpmVersion":"%s","ubuntuVersion":"%s","prootVersion":"%s","tallocVersion":"%s","androidShmemVersion":"%s","build":{"inputsSha256_12":"%s","ubuntuBaseSha256":"%s","pnpmIntegrity":"%s","sourceDateEpoch":%s}}' \
    "$runtime_version" "$DSH_VERSION" "$NODE_VERSION" "$PNPM_VERSION" "$UBUNTU_VERSION" "$PROOT_VERSION" "$TALLOC_VERSION" "$SHMEM_VERSION" \
    "$inputs_sha" "$(sha256 "$base")" "$PNPM_INTEGRITY" "$SOURCE_DATE_EPOCH")"
  "${PY[@]}" manifest --abi "$abi" --report "$out/build-report.json" --jnilibs "$work/jnilibs.json" \
    --versions "$versions" --out "$assets/manifest.json" >&2

  # -- verification: every jniLib and every ELF in the rootfs must match the ABI --
  local f bad=0
  for f in "$jni"/*.so; do
    file -b "$f" | grep -q "$elf_name" || { log "ABI mismatch: $f: $(file -b "$f")"; bad=1; }
  done
  python3 -I - "$out/build-report.json" "$abi" <<'PYEOF' || bad=1
import json, sys
r = json.load(open(sys.argv[1])); want = {"arm64-v8a": "aarch64", "x86_64": "x86-64"}[sys.argv[2]]
wrong = [e["path"] for e in r["elf"] if e["machine"] != want]
print("rootfs ELF objects: %d, wrong-arch: %d, warnings: %d" % (len(r["elf"]), len(wrong), len(r["warnings"])), file=sys.stderr)
sys.exit(1 if wrong else 0)
PYEOF
  [[ "$bad" == 0 ]] || die "ABI verification failed for $abi"
  log "$abi done: $(du -h "$archive" | cut -f1) archive, version $runtime_version"
}

write_index() {
  python3 -I - "$OUT" <<'PYEOF'
import json, os, sys
out = sys.argv[1]
index = {"schema": 1, "abis": {}}
for abi in ("x86_64", "arm64-v8a"):
    p = os.path.join(out, abi, "assets", "runtime", "manifest.json")
    if not os.path.isfile(p):
        continue
    m = json.load(open(p))
    index["abis"][abi] = {k: m.get(k) for k in (  # .get: another ABI may still hold an older build
        "runtimeVersion", "rootfs", "rootfsSha256", "rootfsBytes", "uncompressedBytes", "entries",
        "dshVersion", "nodeVersion", "pnpmVersion", "ubuntuVersion", "prootVersion")}
    index["abis"][abi]["jniLibs"] = {n: {"sha256": v["sha256"], "bytes": v["bytes"]} for n, v in m["jniLibs"].items()}
json.dump(index, open(os.path.join(out, "manifest.json"), "w"), indent=2)
print("wrote " + os.path.join(out, "manifest.json"))
PYEOF
}

main() {
  local -a abis=("$@")
  [[ ${#abis[@]} -gt 0 ]] || abis=(x86_64 arm64-v8a)
  mkdir -p "$CACHE" "$WORK" "$OUT"
  local abi
  for abi in "${abis[@]}"; do build_abi "$abi"; done
  write_index
}

main "$@"
