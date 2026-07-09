#!/usr/bin/env bash
#
# gen-custom-emoji-json.sh — build a custom_emoji.json for a Signal+ emoji pack.
#
# Run it inside (or point it at) a directory full of emote image files. Every
# regular file becomes one emote entry:
#
#     token   = file name WITHOUT its extension     ("pepega.webp" -> "pepega")
#     file    = the file name WITH its extension     ("pepega.webp")
#     aliases = []   (left empty on purpose — fill these in by hand afterwards)
#
# The result is written to custom_emoji.json in that same directory, ready to be
# zipped up and imported from  Signal+ → Settings → Appearance → import pack.
#
# Usage:
#     gen-custom-emoji-json.sh [DIR]      # DIR defaults to the current directory

set -euo pipefail
shopt -s nullglob

dir=${1:-.}
out="custom_emoji.json"
self=$(basename "$0")

cd "$dir"

# Minimal JSON string escaping: backslash and double-quote (enough for file names).
json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  printf '%s' "$s"
}

entries=()
declare -A seen_tokens=()

for f in *; do
  if [ ! -f "$f" ];      then continue; fi   # regular files only (skip sub-directories)
  if [ "$f" = "$out" ];  then continue; fi   # never list the output file itself
  if [ "$f" = "$self" ]; then continue; fi   # nor this script, if it lives in the dir

  token=${f%.*}                              # strip the last extension
  if [ -z "$token" ]; then token=$f; fi      # fallback for dot-only names

  if [ -n "${seen_tokens[$token]:-}" ]; then
    printf 'warning: token "%s" already taken by "%s" — skipping "%s"\n' \
      "$token" "${seen_tokens[$token]}" "$f" >&2
    continue
  fi
  seen_tokens[$token]=$f

  entries+=("    { \"token\": \"$(json_escape "$token")\", \"file\": \"$(json_escape "$f")\", \"aliases\": [] }")
done

if [ ${#entries[@]} -eq 0 ]; then
  echo "No files found in '$dir' — nothing written." >&2
  exit 1
fi

{
  echo '{'
  echo '  "emotes": ['
  for i in "${!entries[@]}"; do
    if [ "$i" -lt $(( ${#entries[@]} - 1 )) ]; then
      printf '%s,\n' "${entries[$i]}"
    else
      printf '%s\n'  "${entries[$i]}"
    fi
  done
  echo '  ]'
  echo '}'
} > "$out"

printf 'Wrote %s with %d emote(s).\n' "$out" "${#entries[@]}" >&2
