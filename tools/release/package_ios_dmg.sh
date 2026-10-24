#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  tools/release/package_ios_dmg.sh \
    --development-ipa PATH \
    --unsigned-ipa PATH \
    [--output PATH] \
    [--version VERSION]

The output defaults to tmp/validation/release-VERSION/PitPitch-VERSION-iOS.dmg.
The script verifies both IPAs, writes checksums and installation notes, creates
a compressed DMG, and verifies the resulting image.
EOF
}

fail() {
  printf 'iOS DMG packaging failed: %s\n' "$1" >&2
  exit 1
}

plist_value() {
  local ipa="$1"
  local key="$2"
  unzip -p "$ipa" 'Payload/*.app/Info.plist' |
    plutil -extract "$key" raw -o - -
}

development_ipa=""
unsigned_ipa=""
output=""
version=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --development-ipa)
      development_ipa="${2:-}"
      shift 2
      ;;
    --unsigned-ipa)
      unsigned_ipa="${2:-}"
      shift 2
      ;;
    --output)
      output="${2:-}"
      shift 2
      ;;
    --version)
      version="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$development_ipa" ]] || fail "--development-ipa is required"
[[ -n "$unsigned_ipa" ]] || fail "--unsigned-ipa is required"
[[ -f "$development_ipa" ]] || fail "development IPA not found: $development_ipa"
[[ -f "$unsigned_ipa" ]] || fail "unsigned IPA not found: $unsigned_ipa"
command -v hdiutil >/dev/null || fail "hdiutil is unavailable"
command -v plutil >/dev/null || fail "plutil is unavailable"
command -v unzip >/dev/null || fail "unzip is unavailable"

development_version="$(plist_value "$development_ipa" CFBundleShortVersionString)"
unsigned_version="$(plist_value "$unsigned_ipa" CFBundleShortVersionString)"
development_build="$(plist_value "$development_ipa" CFBundleVersion)"
unsigned_build="$(plist_value "$unsigned_ipa" CFBundleVersion)"
development_bundle="$(plist_value "$development_ipa" CFBundleIdentifier)"
unsigned_bundle="$(plist_value "$unsigned_ipa" CFBundleIdentifier)"

version="${version:-$development_version}"
[[ "$version" =~ ^0\.[0-9]+\.[0-9]+$ ]] || fail "invalid version: $version"
[[ "$development_version" == "$version" ]] ||
  fail "development IPA version is $development_version, expected $version"
[[ "$unsigned_version" == "$version" ]] ||
  fail "unsigned IPA version is $unsigned_version, expected $version"
[[ "$development_build" == "$unsigned_build" ]] ||
  fail "IPA build numbers differ"
[[ "$development_bundle" == "$unsigned_bundle" ]] ||
  fail "IPA bundle identifiers differ"
[[ "$development_bundle" == "com.cgl.pitpitch" ]] ||
  fail "unexpected bundle identifier: $development_bundle"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -z "$output" ]]; then
  output="$root/tmp/validation/release-$version/PitPitch-$version-iOS.dmg"
fi
output_dir="$(cd "$(dirname "$output")" 2>/dev/null && pwd)" ||
  fail "output directory does not exist: $(dirname "$output")"
output="$output_dir/$(basename "$output")"
[[ "$output" == *.dmg ]] || fail "output must end in .dmg"

stage="$(mktemp -d "${TMPDIR:-/tmp}/pitpitch-ios-dmg.XXXXXX")"
mountpoint="$(mktemp -d "${TMPDIR:-/tmp}/pitpitch-ios-mount.XXXXXX")"
cleanup() {
  if mount | grep -Fq "on $mountpoint "; then
    hdiutil detach "$mountpoint" >/dev/null || true
  fi
  rm -rf "$stage" "$mountpoint"
}
trap cleanup EXIT

development_name="PitPitch-$version-development.ipa"
unsigned_name="PitPitch-$version-unsigned.ipa"
cp "$development_ipa" "$stage/$development_name"
cp "$unsigned_ipa" "$stage/$unsigned_name"

(
  cd "$stage"
  shasum -a 256 "$development_name" > "$development_name.sha256"
  shasum -a 256 "$unsigned_name" > "$unsigned_name.sha256"
)

cat > "$stage/安装说明.txt" <<EOF
PitPitch iOS ${version}（${development_build}）

DMG 是 macOS 磁盘镜像容器，iPhone 不能直接安装 DMG。

从 DMG 中取出 IPA，再通过 Xcode、Apple Configurator 或已配置的签名分发流程安装。

$development_name
- arm64 Apple Development 签名包。
- 只能安装到描述文件中登记的测试设备。
- 证书、设备登记和描述文件有效期以实际签名文件为准。

$unsigned_name
- 只包含编译后的 Payload。
- 不包含签名、描述文件或安装权限。
- 不能直接安装，必须使用自己的 Apple 开发者证书和描述文件重签。

本项目尚未上架 App Store，也不提供 TestFlight 包。
EOF

rm -f "$output" "$output.sha256"
hdiutil create \
  -quiet \
  -volname "PitPitch $version" \
  -srcfolder "$stage" \
  -format UDZO \
  "$output"
hdiutil verify "$output" >/dev/null
hdiutil attach -readonly -nobrowse -mountpoint "$mountpoint" "$output" >/dev/null

for name in "$development_name" "$unsigned_name" "$development_name.sha256" "$unsigned_name.sha256"; do
  [[ -f "$mountpoint/$name" ]] || fail "DMG is missing $name"
done
(
  cd "$mountpoint"
  shasum -a 256 -c "$development_name.sha256"
  shasum -a 256 -c "$unsigned_name.sha256"
)

hdiutil detach "$mountpoint" >/dev/null
shasum -a 256 "$output" > "$output.sha256"

printf 'Created %s\n' "$output"
printf 'Created %s\n' "$output.sha256"
