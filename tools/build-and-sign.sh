#!/usr/bin/env bash
#
# build-and-sign.sh — Build, sign, and collect the Signal+ (website flavor) release APKs.
#
# Pipeline:
#   1. ./gradlew assembleWebsiteProdRelease         -> unsigned release APKs
#   2. zipalign + apksigner sign  (with YOUR key)   -> aligned, signed APKs
#   3. drop the signed .apk files into tools/release/
#
# Usage:
#   tools/build-and-sign.sh [options] [abi ...]
#
#   abi ...      One or more of: universal arm64-v8a armeabi-v7a x86 x86_64
#                (default: all of them)
#
# Options:
#   --no-build   Skip the Gradle build; just (re)sign whatever is already in
#                app/build/outputs/apk/websiteProd/release/
#   --clean      Run `gradlew clean` before building
#   -h, --help   Show this help
#
# Configuration (environment variables):
#   SIGNALPLUS_KEYSTORE     Path to your release keystore
#                           (default: ~/signal-plus-release.jks)
#   SIGNALPLUS_KEY_ALIAS    Key alias inside the keystore (default: signalplus)
#   SIGNALPLUS_STORE_PASS   Keystore password. If unset, apksigner prompts.
#   SIGNALPLUS_KEY_PASS     Key password.      If unset, apksigner prompts.
#   SIGNALPLUS_RELEASE_DIR  Output dir for signed APKs (default: tools/release)
#   ANDROID_HOME            Android SDK path. Auto-detected from $ANDROID_HOME,
#                           $ANDROID_SDK_ROOT, ./local.properties, then ~/Android/Sdk.
#
# First-time setup — create your release key ONCE and keep it + its password
# safe; every future update MUST be signed with the same key or Android will
# refuse to install over the previous version:
#
#   keytool -genkeypair -v -keystore ~/signal-plus-release.jks \
#       -alias signalplus -keyalg RSA -keysize 4096 -validity 10000
#
set -euo pipefail

# ---- locate repo root (this script lives in tools/) --------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- defaults / config ------------------------------------------------------
KEYSTORE="${SIGNALPLUS_KEYSTORE:-$HOME/signal-plus-release.jks}"
KEY_ALIAS="${SIGNALPLUS_KEY_ALIAS:-signalplus}"
RELEASE_DIR="${SIGNALPLUS_RELEASE_DIR:-$SCRIPT_DIR/release}"
ALL_ABIS=(universal arm64-v8a armeabi-v7a x86 x86_64)

DO_BUILD=1
DO_CLEAN=0
ABIS=()

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

usage() { awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"; }

# ---- parse args -------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) DO_BUILD=0 ;;
    --clean)    DO_CLEAN=1 ;;
    -h|--help)  usage; exit 0 ;;
    -*)         die "Unknown option: $1 (see --help)" ;;
    *)
      # validate ABI name
      valid=0; for a in "${ALL_ABIS[@]}"; do [[ "$1" == "$a" ]] && valid=1; done
      [[ $valid -eq 1 ]] || die "Unknown ABI '$1'. Valid: ${ALL_ABIS[*]}"
      ABIS+=("$1")
      ;;
  esac
  shift
done
[[ ${#ABIS[@]} -eq 0 ]] && ABIS=("${ALL_ABIS[@]}")

# ---- detect Android SDK + latest build-tools --------------------------------
detect_sdk() {
  if [[ -n "${ANDROID_HOME:-}" ]];     then printf '%s\n' "$ANDROID_HOME";     return; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then printf '%s\n' "$ANDROID_SDK_ROOT"; return; fi
  if [[ -f "$REPO_ROOT/local.properties" ]]; then
    local d
    d="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1)"
    d="${d//\\:/:}"   # unescape a Windows-style "C\:" path if present
    [[ -n "$d" ]] && { printf '%s\n' "$d"; return; }
  fi
  printf '%s\n' "$HOME/Android/Sdk"
}

SDK="$(detect_sdk)"
[[ -d "$SDK" ]] || die "Android SDK not found at '$SDK'. Set ANDROID_HOME or fix local.properties (sdk.dir)."

BUILD_TOOLS_DIR="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
[[ -n "$BUILD_TOOLS_DIR" ]] || die "No build-tools found under $SDK/build-tools."
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
[[ -x "$APKSIGNER" ]] || die "apksigner not found/executable at $APKSIGNER"
[[ -x "$ZIPALIGN"  ]] || die "zipalign not found/executable at $ZIPALIGN"

# ---- keystore must exist (fail fast, before the long build) -----------------
if [[ ! -f "$KEYSTORE" ]]; then
  die "Keystore not found: $KEYSTORE

Create your release key once (then re-run this script):

  keytool -genkeypair -v -keystore \"$KEYSTORE\" \\
      -alias \"$KEY_ALIAS\" -keyalg RSA -keysize 4096 -validity 10000

Or point SIGNALPLUS_KEYSTORE / SIGNALPLUS_KEY_ALIAS at an existing key."
fi

log "SDK:         $SDK"
log "build-tools: $(basename "$BUILD_TOOLS_DIR")"
log "keystore:    $KEYSTORE  (alias: $KEY_ALIAS)"
log "ABIs:        ${ABIS[*]}"

# ---- build ------------------------------------------------------------------
BUILD_OUT="$REPO_ROOT/app/build/outputs/apk/websiteProd/release"
cd "$REPO_ROOT"

if [[ $DO_CLEAN -eq 1 ]]; then
  log "Gradle clean"
  ./gradlew clean --console=plain
fi

if [[ $DO_BUILD -eq 1 ]]; then
  log "Building website prod release APKs (this can take several minutes)…"
  # NB: unqualified task name — the app module's Gradle name is :Signal-Android, not :app
  ./gradlew assembleWebsiteProdRelease --console=plain
else
  log "Skipping build (--no-build); signing existing outputs."
fi

[[ -d "$BUILD_OUT" ]] || die "Build output dir missing: $BUILD_OUT (did the build run?)"

# ---- read THIS build's version from output-metadata.json --------------------
# output-metadata.json always describes the outputs of the current build, so it's the source of truth
# for which version was just built. Selecting the unsigned APK by this exact version (instead of a
# "*.apk" glob) means a leftover older/newer-version APK sitting in $BUILD_OUT can't be signed by
# mistake — the bug that let a stale version get published after a version bump was reverted.
METADATA="$BUILD_OUT/output-metadata.json"
[[ -f "$METADATA" ]] || die "output-metadata.json not found in $BUILD_OUT (did the build run?)."
VERSION_NAME="$(sed -nE 's/.*"versionName" *: *"([^"]+)".*/\1/p' "$METADATA" | head -1)"
[[ -n "$VERSION_NAME" ]] || die "Could not read versionName from $METADATA."
log "build version: $VERSION_NAME"

# ---- sign each requested ABI into the release dir ---------------------------
mkdir -p "$RELEASE_DIR"
RELEASE_DIR="$(cd "$RELEASE_DIR" && pwd)"   # normalize to absolute

# Each APK is aligned + signed under a hidden ".partial-" name (which publish-update.sh's
# "Signal-Plus-…" glob can't match) and only moved into place once it verifies. A failed or aborted
# signing — e.g. a password prompt with no stdin — must never leave an unsigned APK under the release
# name, where publish-update.sh would ship it. This trap removes the in-progress file on any exit.
partial=""
trap '[[ -n "$partial" ]] && rm -f "$partial"' EXIT

signed=()
for abi in "${ABIS[@]}"; do
  # Exact filename for THIS build's version — no globbing, so a leftover older-version APK in
  # $BUILD_OUT can never be picked by mistake.
  unsigned="$BUILD_OUT/Signal-Android-website-prod-${abi}-release-unsigned-${VERSION_NAME}.apk"
  if [[ ! -f "$unsigned" ]]; then
    warn "No unsigned APK for ABI '$abi' at version $VERSION_NAME in $BUILD_OUT — skipping."
    continue
  fi

  base="$(basename "$unsigned")"
  out_name="${base/Signal-Android/Signal-Plus}"   # brand it
  out_name="${out_name/-unsigned/}"               # it's signed now
  out="$RELEASE_DIR/$out_name"
  partial="$RELEASE_DIR/.partial-$out_name"

  log "Signing $abi -> $out_name"
  "$ZIPALIGN" -p -f 4 "$unsigned" "$partial"

  sign_cmd=("$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --v4-signing-enabled false)
  [[ -n "${SIGNALPLUS_STORE_PASS:-}" ]] && sign_cmd+=(--ks-pass env:SIGNALPLUS_STORE_PASS)
  [[ -n "${SIGNALPLUS_KEY_PASS:-}"   ]] && sign_cmd+=(--key-pass env:SIGNALPLUS_KEY_PASS)
  sign_cmd+=("$partial")
  "${sign_cmd[@]}" || die "Signing $abi failed — release dir left untouched."

  "$APKSIGNER" verify "$partial" >/dev/null || die "Signed $abi APK does not verify — release dir left untouched."

  # Only now, with a verified APK in hand, drop any previously-signed APK for this ABI (any version)
  # so the release dir only ever holds the current build. publish-update.sh picks the highest-versioned
  # APK it finds there, so a stale higher-version leftover (e.g. after reverting a version bump) would
  # otherwise get published instead. The "-release-" anchor keeps the x86 glob from also matching x86_64.
  rm -f "$RELEASE_DIR"/Signal-Plus-website-prod-"${abi}"-release-*.apk
  mv -f "$partial" "$out"
  partial=""
  signed+=("$out")
done

[[ ${#signed[@]} -gt 0 ]] || die "Nothing was signed."

# ---- summary ----------------------------------------------------------------
echo
log "Signed & verified APKs in $RELEASE_DIR:"
for f in "${signed[@]}"; do
  printf '    %s  (%s)\n' "$(basename "$f")" "$(du -h "$f" | cut -f1)"
done

echo
log "Signing certificate (record this — every future update must use the same key):"
"$APKSIGNER" verify --print-certs "${signed[0]}" | grep -i 'SHA-256' | head -1 | sed 's/^/    /' || true

echo
# pick a sensible install example: prefer arm64-v8a (most phones), else the first signed
example="${signed[0]}"
for f in "${signed[@]}"; do [[ "$f" == *arm64-v8a* ]] && example="$f"; done
log "Install with, e.g.:  adb install \"$example\""
