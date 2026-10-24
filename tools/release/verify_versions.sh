#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle_file="$root/android/app/build.gradle.kts"
project_file="$root/ios/project.yml"
pbxproj_file="$root/ios/PitPitch.xcodeproj/project.pbxproj"

fail() {
  printf 'Version check failed: %s\n' "$1" >&2
  exit 1
}

android_versions="$(
  sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*$/\1/p' "$gradle_file" |
    sort -u
)"
android_codes="$(
  sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' "$gradle_file" |
    sort -u
)"
ios_versions="$(
  awk '$1 == "MARKETING_VERSION:" { gsub(/"/, "", $2); print $2 }' "$project_file" |
    sort -u
)"
ios_builds="$(
  awk '$1 == "CURRENT_PROJECT_VERSION:" { gsub(/"/, "", $2); print $2 }' "$project_file" |
    sort -u
)"
pbx_versions="$(
  sed -nE 's/^[[:space:]]*MARKETING_VERSION = ([^;]+);.*$/\1/p' "$pbxproj_file" |
    sort -u
)"
pbx_builds="$(
  sed -nE 's/^[[:space:]]*CURRENT_PROJECT_VERSION = ([^;]+);.*$/\1/p' "$pbxproj_file" |
    sort -u
)"

[[ -n "$android_versions" ]] || fail "Android versionName not found"
[[ -n "$android_codes" ]] || fail "Android versionCode not found"
[[ -n "$ios_versions" ]] || fail "iOS MARKETING_VERSION not found"
[[ -n "$ios_builds" ]] || fail "iOS CURRENT_PROJECT_VERSION not found"
[[ "$android_versions" != *$'\n'* ]] || fail "Android versionName is not unique"
[[ "$android_codes" != *$'\n'* ]] || fail "Android versionCode is not unique"
[[ "$ios_versions" != *$'\n'* ]] || fail "iOS MARKETING_VERSION is not unique"
[[ "$ios_builds" != *$'\n'* ]] || fail "iOS CURRENT_PROJECT_VERSION is not unique"

[[ "$pbx_versions" == "$ios_versions" ]] ||
  fail "project.yml and project.pbxproj MARKETING_VERSION differ"
[[ "$pbx_builds" == "$ios_builds" ]] ||
  fail "project.yml and project.pbxproj CURRENT_PROJECT_VERSION differ"
[[ "$android_versions" == "$ios_versions" ]] ||
  fail "Android and iOS marketing versions differ"
[[ "$android_versions" =~ ^0\.[0-9]+\.[0-9]+$ ]] ||
  fail "marketing version must use 0.x.x"
[[ "$android_codes" =~ ^[1-9][0-9]*$ ]] || fail "Android versionCode must be positive"
[[ "$ios_builds" =~ ^[1-9][0-9]*$ ]] || fail "iOS build number must be positive"

printf 'Unified version OK: %s (Android %s / iOS %s)\n' \
  "$android_versions" "$android_codes" "$ios_builds"
