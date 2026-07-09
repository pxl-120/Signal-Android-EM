#!/usr/bin/env bash
#
# 7tv-download-set.sh
# ------------------------------------------------------------------------------
# Download every emote from a PUBLIC 7TV emote set via the 7TV API.
#
#   - static emotes   -> PNG
#   - animated emotes -> GIF
#   - size: 2x preferred. If 2x is missing, the next larger size is used.
#           If only one size exists, that one is used.
#   - if a set somehow lacks PNG/GIF for an emote, it falls back to WEBP
#     (then AVIF) at the same size and prints a warning.
#
# Usage:
#   ./7tv-download-set.sh <emote-set-id-or-url> [output-dir]
#
# Examples:
#   ./7tv-download-set.sh 01KW1FQR9PT5F8G0FW6VXVJCKM ./emotes
#   ./7tv-download-set.sh https://7tv.app/emote-sets/01KW1FQR9PT5F8G0FW6VXVJCKM my_emotes
#
# Env overrides:
#   CONCURRENCY=10   # parallel downloads (default 6)
#   FORCE=1          # re-download files that already exist (default: skip them)
#
# Requires: bash, curl, jq
# ------------------------------------------------------------------------------

set -euo pipefail

API_BASE="https://7tv.io/v3/emote-sets"
UA="7tv-set-downloader/1.0"
CONCURRENCY="${CONCURRENCY:-6}"
FORCE="${FORCE:-0}"

# ---- args -------------------------------------------------------------------
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <emote-set-id-or-url> [output-dir]" >&2
  exit 1
fi

RAW_ID="$1"
OUT_DIR="${2:-./emotes}"

# Accept either a bare id or a full 7tv.app URL; pull the trailing path segment.
SET_ID="${RAW_ID%%\?*}"   # drop any ?query
SET_ID="${SET_ID%/}"      # drop a trailing slash
SET_ID="${SET_ID##*/}"    # keep only the last path segment
if [ -z "$SET_ID" ]; then
  echo "Could not parse an emote-set id from: $RAW_ID" >&2
  exit 1
fi

# ---- deps -------------------------------------------------------------------
for dep in curl jq; do
  if ! command -v "$dep" >/dev/null 2>&1; then
    echo "Missing required tool: '$dep'." >&2
    echo "Install it and re-run, e.g.  'sudo apt install $dep'  or  'brew install $dep'." >&2
    exit 1
  fi
done

mkdir -p "$OUT_DIR"

# ---- temp files + cleanup ---------------------------------------------------
TMP_JSON="$(mktemp)"
MANIFEST="$(mktemp)"
trap 'rm -f "$TMP_JSON" "$MANIFEST"' EXIT

# ---- fetch the set ----------------------------------------------------------
echo ">> Fetching emote set: $SET_ID"
if ! curl -fsSL -A "$UA" -H 'Accept: application/json' \
        --connect-timeout 15 --max-time 60 \
        "$API_BASE/$SET_ID" -o "$TMP_JSON"; then
  echo "Failed to fetch the emote set from the 7TV API." >&2
  echo "Check the id/URL and your internet connection." >&2
  exit 1
fi

if ! jq -e 'has("emotes")' "$TMP_JSON" >/dev/null 2>&1; then
  echo "The API response is not a valid emote set (wrong id, private, or removed)." >&2
  exit 1
fi

SET_NAME="$(jq -r '.name // "unknown"' "$TMP_JSON")"
TOTAL="$(jq -r '.emotes | length' "$TMP_JSON")"
echo ">> Set: \"$SET_NAME\"  ($TOTAL emote(s))"

if [ "$TOTAL" -eq 0 ]; then
  echo ">> Set is empty, nothing to download."
  exit 0
fi

# ---- choose a file per emote and build a NUL-delimited download manifest -----
# jq emits TSV rows: url \t outpath \t wanted_format \t used_format
# We turn that into a NUL-delimited "url\0outpath\0" manifest so that any
# characters in the file name (spaces, unicode, etc.) are handled safely.
echo ">> Selecting formats/sizes ..."
while IFS=$'\t' read -r url outpath want used; do
  [ -z "$url" ] && continue
  if [ "$want" != "$used" ]; then
    echo "   WARN: '${outpath##*/}': no $want available for this emote, using $used instead" >&2
  fi
  printf '%s\0%s\0' "$url" "$outpath" >>"$MANIFEST"
done < <(
  jq -r --arg outdir "$OUT_DIR" '
    # numeric scale from a file name like "2x.png" -> 2
    def scale_of: (((.name | sub("x.*$"; "")) | tonumber?) // 0);
    # make an emote name safe to use as a file name
    def safe: gsub("[\\\\/:*?\"<>|]"; "_") | gsub("\\s"; "_") | gsub("^\\.+"; "_");

    .emotes[]
    | select(.data.host.url != null)
    | .name as $name
    | (.data.animated // false) as $anim
    | (if $anim then "GIF" else "PNG" end) as $want
    | ("https:" + .data.host.url) as $base
    | (.data.host.files // []) as $files
    | ([ $files[] | select(.format == $want) ]) as $primary
    # format fallback chain if the requested one is unavailable
    | (if   ($primary | length) > 0 then {list:$primary, fmt:$want}
       elif ([ $files[] | select(.format=="WEBP") ] | length) > 0 then {list:[ $files[] | select(.format=="WEBP") ], fmt:"WEBP"}
       elif ([ $files[] | select(.format=="AVIF") ] | length) > 0 then {list:[ $files[] | select(.format=="AVIF") ], fmt:"AVIF"}
       else {list:$files, fmt:"?"} end) as $pick
    | ($pick.list | map(. + {scale: scale_of})) as $scaled
    # size selection: prefer 2x; else smallest size > 2x; else the only/largest available
    | ( ($scaled | map(select(.scale == 2)) | .[0])
        // ($scaled | map(select(.scale > 2)) | sort_by(.scale) | .[0])
        // ($scaled | sort_by(.scale) | .[-1]) ) as $chosen
    | select($chosen != null)
    | ($chosen.name | split(".") | .[-1]) as $ext
    | [ ($base + "/" + $chosen.name),
        ($outdir + "/" + ($name | safe) + "." + $ext),
        $want, $pick.fmt ] | @tsv
  ' "$TMP_JSON"
)

# how many emotes actually produced a download target
NUL_COUNT="$(tr -cd '\0' <"$MANIFEST" | wc -c | tr -d ' ')"
PAIRS="$(( NUL_COUNT / 2 ))"
if [ "$PAIRS" -lt "$TOTAL" ]; then
  echo ">> Note: $((TOTAL - PAIRS)) emote(s) had no usable files and were skipped." >&2
fi
if [ "$PAIRS" -eq 0 ]; then
  echo ">> Nothing to download."
  exit 0
fi

# ---- download ---------------------------------------------------------------
echo ">> Downloading $PAIRS emote(s) into '$OUT_DIR'  (concurrency $CONCURRENCY) ..."

fetch_one() {
  local url="$1" out="$2"
  if [ "${FORCE:-0}" != "1" ] && [ -s "$out" ]; then
    printf '   skip %s (exists)\n' "${out##*/}"
    return 0
  fi
  if curl -fsSL -A "$UA" --retry 3 --retry-delay 2 --retry-connrefused \
         --connect-timeout 15 --max-time 120 -o "$out.part" "$url"; then
    mv -f "$out.part" "$out"
    printf '   ok   %s\n' "${out##*/}"
  else
    rm -f "$out.part"
    printf '   ERR  %s\n' "${out##*/}" >&2
  fi
}
export -f fetch_one
export UA FORCE

# xargs feeds two NUL tokens per command -> $1 (url) and $2 (outpath)
xargs -0 -P "$CONCURRENCY" -n 2 \
  bash -c 'fetch_one "$1" "$2"' seventv-dl <"$MANIFEST" || true

# ---- tally ------------------------------------------------------------------
present=0; missing=0
while IFS= read -r -d '' _url && IFS= read -r -d '' outp; do
  if [ -s "$outp" ]; then present=$((present+1)); else missing=$((missing+1)); fi
done <"$MANIFEST"

echo ">> Done. $present/$PAIRS file(s) present in '$OUT_DIR'."
if [ "$missing" -gt 0 ]; then
  echo ">> $missing download(s) failed. Re-run the same command to retry just the missing ones." >&2
  exit 1
fi
