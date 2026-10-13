#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
out="gradle/wrapper/gradle-wrapper.jar"
expected="498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
if [[ -f "$out" ]]; then
  actual=$(shasum -a 256 "$out" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || { echo "现有 Wrapper 校验失败" >&2; exit 1; }
  echo "Gradle 8.9 Wrapper 校验通过"
  exit 0
fi
mkdir -p gradle/wrapper tmp/validation
download=$(mktemp "tmp/validation/wrapper.XXXXXX")
trap 'rm -f "$download"' EXIT
curl --fail --location --retry 2 --connect-timeout 10 --max-time 120 --silent --show-error \
  "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" -o "$download"
actual=$(shasum -a 256 "$download" | awk '{print $1}')
[[ "$actual" == "$expected" ]] || { echo "下载的 Wrapper 校验失败" >&2; exit 1; }
mv "$download" "$out"
echo "Gradle 8.9 Wrapper 已从官方仓库恢复"
