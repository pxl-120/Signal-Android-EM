#!/usr/bin/env bash
#
# publish-update.sh — Publish a Signal+ self-update.
#
# Generates the update manifest that the in-app updater (ApkUpdateJob) expects and
# cuts a GitHub Release, so already-installed Signal+ clients auto-download + prompt
# to install the new version.
#
# Pipeline:
#   1. Locate the signed APK for the target ABI (default arm64-v8a) in tools/release/
#      (run build-and-sign.sh first, or pass --build to run it here).
#   2. Read versionCode / versionName straight from that APK (aapt2).
#   3. Compute its SHA-256.
#   4. Write tools/release/update.json  (the manifest ApkUpdateJob parses).
#   5. Create/update a GitHub Release tagged v<versionName>, attaching the APK (as a
#      STABLE asset name) and update.json — so the baked-in
#      .../releases/latest/download/... URLs always resolve to the newest release.
#
# Usage:
#   tools/publish-update.sh [--build] [--draft] [--abi <name>]
#
#   --build       Run tools/build-and-sign.sh for the target ABI first.
#   --draft       Create the GitHub release as a draft (you publish it manually).
#   --abi <name>  ABI to publish: universal|arm64-v8a|armeabi-v7a|x86|x86_64
#                 (default: arm64-v8a — all modern 64-bit phones, ~half of universal).
#   -h,--help     Show this help.
#
# Prerequisites:
#   - The signed APK must be built with the SAME key as every prior release (Android
#     refuses cross-key updates). build-and-sign.sh handles this.
#   - `gh` CLI installed and authenticated: gh auth login   (needs repo write access).
#   - MANAGES_APP_UPDATES=true + APK_UPDATE_MANIFEST_URL must be set in the website
#     flavor (app/build.gradle.kts) and pointed at THIS repo's latest/download/update.json.
#
# Config (environment variables):
#   SIGNALPLUS_RELEASE_DIR  signed-APK dir (default: tools/release)
#   SIGNALPLUS_UPDATE_ABI   ABI to publish (default: arm64-v8a)
#   GH_REPO                 owner/repo   (default: parsed from `git remote get-url origin`)
#   ASSET_APK_NAME          release asset name (default: Signal-Plus-<abi>.apk)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RELEASE_DIR="${SIGNALPLUS_RELEASE_DIR:-$SCRIPT_DIR/release}"
ABI="${SIGNALPLUS_UPDATE_ABI:-arm64-v8a}"
ALL_ABIS=(universal arm64-v8a armeabi-v7a x86 x86_64)
DO_BUILD=0
DRAFT=0

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
usage() { awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)   DO_BUILD=1 ;;
    --draft)   DRAFT=1 ;;
    --abi)     [[ $# -ge 2 ]] || die "--abi needs an ABI name"; ABI="$2"; shift 2; continue ;;
    -h|--help) usage; exit 0 ;;
    *)         die "Unknown argument: $1 (see --help)" ;;
  esac
  shift
done

[[ " ${ALL_ABIS[*]} " == *" $ABI "* ]] || die "Unknown ABI '$ABI'. Valid: ${ALL_ABIS[*]}"
ASSET_APK_NAME="${ASSET_APK_NAME:-Signal-Plus-${ABI}.apk}"

command -v gh >/dev/null || die "gh CLI not found. Install it and run: gh auth login"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"

# ---- resolve target repo (owner/repo) ---------------------------------------
if [[ -z "${GH_REPO:-}" ]]; then
  origin="$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)"
  # handles https://github.com/OWNER/REPO(.git) and git@github.com:OWNER/REPO(.git)
  GH_REPO="$(printf '%s\n' "$origin" | sed -E 's#(git@github.com:|https?://github.com/)##; s#\.git$##')"
fi
[[ "$GH_REPO" == */* ]] || die "Could not determine GH_REPO (owner/repo). Set GH_REPO=owner/repo."

# ---- optionally build+sign first --------------------------------------------
if [[ $DO_BUILD -eq 1 ]]; then
  log "Building + signing $ABI APK…"
  "$SCRIPT_DIR/build-and-sign.sh" "$ABI"
fi

# ---- locate the signed universal APK ----------------------------------------
[[ -d "$RELEASE_DIR" ]] || die "Release dir not found: $RELEASE_DIR (build+sign first, or pass --build)."
APK="$(find "$RELEASE_DIR" -maxdepth 1 -type f -name "Signal-Plus-website-prod-${ABI}-release-*.apk" 2>/dev/null | sort -V | tail -1)"
[[ -n "$APK" ]] || die "No signed $ABI APK in $RELEASE_DIR. Run build-and-sign.sh $ABI (or pass --build)."
log "APK: $(basename "$APK")"

# ---- find aapt2 (to read versionCode/versionName from the APK itself) --------
detect_sdk() {
  if [[ -n "${ANDROID_HOME:-}" ]];     then printf '%s\n' "$ANDROID_HOME";     return; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then printf '%s\n' "$ANDROID_SDK_ROOT"; return; fi
  if [[ -f "$REPO_ROOT/local.properties" ]]; then
    local d; d="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1)"
    [[ -n "$d" ]] && { printf '%s\n' "${d//\\:/:}"; return; }
  fi
  printf '%s\n' "$HOME/Android/Sdk"
}
SDK="$(detect_sdk)"
AAPT2="$(find "$SDK/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 -type f 2>/dev/null | sort -V | tail -1)"
[[ -x "$AAPT2" ]] || die "aapt2 not found under $SDK/build-tools."

badging="$("$AAPT2" dump badging "$APK")"
VERSION_CODE="$(printf '%s\n' "$badging" | sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p" | head -1)"
VERSION_NAME="$(printf '%s\n' "$badging" | sed -nE "s/.*versionName='([^']+)'.*/\1/p" | head -1)"
[[ -n "$VERSION_CODE" && -n "$VERSION_NAME" ]] || die "Could not read versionCode/versionName from APK."

SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
# uploadTimestamp is informational for the website flavor (it updates purely on versionCode),
# but ApkUpdateJob logs it, so stamp it with the build's mtime for reproducibility.
UPLOAD_TS="$(( $(stat -c %Y "$APK") * 1000 ))"

APK_URL="https://github.com/$GH_REPO/releases/latest/download/$ASSET_APK_NAME"

log "repo:        $GH_REPO"
log "versionName: $VERSION_NAME"
log "versionCode: $VERSION_CODE"
log "sha256:      $SHA256"

# ---- write the manifest (schema: ApkUpdateJob.UpdateDescriptor) --------------
MANIFEST="$RELEASE_DIR/update.json"
cat > "$MANIFEST" <<JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "url": "$APK_URL",
  "sha256sum": "$SHA256",
  "uploadTimestamp": $UPLOAD_TS
}
JSON
log "Wrote $MANIFEST"

# ---- stage the APK under its STABLE asset name ------------------------------
STABLE_APK="$RELEASE_DIR/$ASSET_APK_NAME"
cp -f "$APK" "$STABLE_APK"

# ---- create or update the GitHub release ------------------------------------
TAG="v$VERSION_NAME"
create_flags=(--title "Signal+ $VERSION_NAME" --notes "Automated Signal+ build $VERSION_NAME (versionCode $VERSION_CODE).")
[[ $DRAFT -eq 1 ]] && create_flags+=(--draft)

if gh release view "$TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
  log "Release $TAG exists — updating assets (--clobber)."
  gh release upload "$TAG" "$STABLE_APK" "$MANIFEST" --repo "$GH_REPO" --clobber
else
  log "Creating release $TAG."
  gh release create "$TAG" "$STABLE_APK" "$MANIFEST" --repo "$GH_REPO" "${create_flags[@]}"
fi

echo
log "Published. Installed Signal+ clients with a LOWER versionCode will update to $VERSION_NAME."
warn "Same-version rebuilds share versionCode $VERSION_CODE and will NOT trigger an update —"
warn "bump canonicalVersionCode (or sync newer upstream) to push a new one."
