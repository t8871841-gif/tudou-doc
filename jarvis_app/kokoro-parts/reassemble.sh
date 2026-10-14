#!/usr/bin/env bash
# Reassemble assets/kokoro/ from kokoro-parts/ (split files for GitHub 100MB / web 25MB limits).
# Usage: run from repo root (jarvis_app/):  bash kokoro-parts/reassemble.sh
# Exits non-zero if any part is missing or SHA-256 mismatch.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PARTS="$ROOT/kokoro-parts"
DEST="$ROOT/assets/kokoro"

MODEL_SHA="4b86207ef680e394d8343bee22dfc4c512e5c707c6d9578e3f35ab09bffd6b36"
VOICES_SHA="1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"

mkdir -p "$DEST"

echo "Merging model.int8.onnx ..."
cat "$PARTS"/model.int8.onnx.part0* > "$DEST/model.int8.onnx"

echo "Merging voices.bin ..."
cat "$PARTS"/voices-v1.part0* > "$DEST/voices.bin"

echo "Unzipping espeak-ng-data ..."
rm -rf "$DEST/espeak-ng-data"
unzip -q -o "$PARTS/espeak-ng-data.zip" -d "$DEST"

echo "Copying small files + dict ..."
cp "$PARTS"/tokens.txt "$PARTS"/lexicon-us-en.txt "$PARTS"/lexicon-gb-en.txt \
   "$PARTS"/lexicon-zh.txt "$PARTS"/phone-zh.fst "$PARTS"/date-zh.fst \
   "$PARTS"/number-zh.fst "$PARTS"/LICENSE "$PARTS"/README.md "$DEST"/
rm -rf "$DEST/dict"
cp -r "$PARTS/dict" "$DEST/dict"

echo "Verifying SHA-256 ..."
echo "$MODEL_SHA  $DEST/model.int8.onnx" | sha256sum -c -
echo "$VOICES_SHA  $DEST/voices.bin" | sha256sum -c -

echo "Sizes:"
stat -c "%s %n" "$DEST/model.int8.onnx" "$DEST/voices.bin"
echo "Reassembly OK: assets/kokoro/ is complete."
