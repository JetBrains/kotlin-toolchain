#!/bin/bash
#
# Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
#

# A fake `xcodebuild` installed into a shadow Xcode developer directory (selected via DEVELOPER_DIR) by tests.
#
# * `archive` is faked: a minimal `<archivePath>/Info.plist` is created.
# * `-exportArchive` with `destination=upload` is faked: the upload is registered with the fake App Store Connect
#   server, and a `Distributions` entry is added to the archive's `Info.plist`, like Xcode does.
# * Everything else is passed through to the real `xcodebuild`.
#
# Environment:
#   FAKE_XCODEBUILD_REAL_DEVELOPER_DIR  the real Xcode developer directory (required)
#   FAKE_XCODEBUILD_LOG                 file to which every invocation's argv is appended as a JSON array line
#   FAKE_XCODEBUILD_MODE                ok (default), archive-fail, upload-fail, upload-no-distribution, upload-state-failed
#   FAKE_XCODEBUILD_BUNDLE_ID           the CFBundleIdentifier to write into the fake archive
#   FAKE_ASC_URL                        base URL of the fake App Store Connect server

set -euo pipefail

REAL_DEVELOPER_DIR="${FAKE_XCODEBUILD_REAL_DEVELOPER_DIR:?FAKE_XCODEBUILD_REAL_DEVELOPER_DIR is not set}"
MODE="${FAKE_XCODEBUILD_MODE:-ok}"
PLIST_BUDDY=/usr/libexec/PlistBuddy

json_string() {
  local s=${1//\\/\\\\}
  s=${s//\"/\\\"}
  printf '"%s"' "$s"
}

log_invocation() {
  [ -n "${FAKE_XCODEBUILD_LOG:-}" ] || return 0
  local line="[" separator=""
  for arg in "$@"; do
    line+="$separator$(json_string "$arg")"
    separator=","
  done
  printf '%s]\n' "$line" >> "$FAKE_XCODEBUILD_LOG"
}

fail() {
  echo "error: [fake xcodebuild] $2" >&2
  exit "$1"
}

plist_value() {
  "$PLIST_BUDDY" -c "Print :$2" "$1" 2>/dev/null || true
}

pass_through() {
  DEVELOPER_DIR="$REAL_DEVELOPER_DIR" exec "$REAL_DEVELOPER_DIR/usr/bin/xcodebuild" "$@"
}

fake_archive() {
  [ -n "$archive_path" ] || fail 64 "archive requires -archivePath"
  if [ "$MODE" = archive-fail ]; then
    fail 65 "archive failed (FAKE_XCODEBUILD_MODE=$MODE)"
  fi
  mkdir -p "$archive_path"
  local plist="$archive_path/Info.plist"
  rm -f "$plist"
  "$PLIST_BUDDY" \
    -c "Add :ArchiveVersion integer 2" \
    -c "Add :Name string App" \
    -c "Add :SchemeName string app" \
    -c "Add :ApplicationProperties dict" \
    -c "Add :ApplicationProperties:CFBundleIdentifier string ${FAKE_XCODEBUILD_BUNDLE_ID:-org.jetbrains.ktc.testflight}" \
    -c "Add :ApplicationProperties:CFBundleShortVersionString string 1.0" \
    -c "Add :ApplicationProperties:CFBundleVersion string 1" \
    "$plist" > /dev/null
  echo "** ARCHIVE SUCCEEDED **"
}

register_upload() {
  local version="$1" build="$2" response
  response=$(curl --silent --show-error --fail --noproxy '*' -X POST \
    -H 'Content-Type: application/json' \
    --data "{\"version\":\"$version\",\"build\":\"$build\"}" \
    "${FAKE_ASC_URL:?FAKE_ASC_URL is not set}/__fake/uploads") || fail 70 "could not register the upload with the fake server"
  sed -E 's/.*"id" *: *"([^"]+)".*/\1/' <<< "$response"
}

add_distribution() {
  local plist="$1" id="$2" state="$3"
  if ! "$PLIST_BUDDY" -c "Print :Distributions" "$plist" > /dev/null 2>&1; then
    "$PLIST_BUDDY" -c "Add :Distributions array" "$plist"
  fi
  # The order of distributions doesn't matter, so the new one is inserted first
  "$PLIST_BUDDY" \
    -c "Add :Distributions:0 dict" \
    -c "Add :Distributions:0:identifier string $id" \
    -c "Add :Distributions:0:destination string upload" \
    -c "Add :Distributions:0:uploadEvent dict" \
    -c "Add :Distributions:0:uploadEvent:state string $state" \
    "$plist"
}

fake_upload() {
  local plist="$archive_path/Info.plist"
  [ -f "$plist" ] || fail 66 "archive metadata is missing: $plist"
  local method team
  method=$(plist_value "$export_options" method)
  team=$(plist_value "$export_options" teamID)
  [ "$method" = app-store-connect ] || fail 64 "unexpected export method '$method'"
  [ -n "$team" ] || fail 64 "teamID is missing from the export options"

  case "$MODE" in
    upload-fail) fail 70 "upload failed (FAKE_XCODEBUILD_MODE=$MODE)" ;;
    upload-no-distribution) echo "** EXPORT SUCCEEDED **"; return 0 ;;
  esac

  local id state=success
  id=$(register_upload \
    "$(plist_value "$plist" ApplicationProperties:CFBundleShortVersionString)" \
    "$(plist_value "$plist" ApplicationProperties:CFBundleVersion)")
  [ "$MODE" = upload-state-failed ] && state=failed
  add_distribution "$plist" "$id" "$state"
  echo "Uploaded App"
  echo "** EXPORT SUCCEEDED **"
}

log_invocation "$@"

archive_path=""
export_options=""
is_archive=false
is_export=false
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[i]}" in
    -archivePath) archive_path="${args[i + 1]:-}" ;;
    -exportOptionsPlist) export_options="${args[i + 1]:-}" ;;
    -exportArchive) is_export=true ;;
    archive) is_archive=true ;;
  esac
done

if $is_archive; then
  fake_archive
elif $is_export && [ -n "$export_options" ] && [ "$(plist_value "$export_options" destination)" = upload ]; then
  fake_upload
else
  pass_through "$@"
fi
