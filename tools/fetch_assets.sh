#!/usr/bin/env bash
# Fetches third-party model and data files that are not committed to git, places them as module
# assets, and prints their SHA-256 so docs/MODEL_AND_DATA_REGISTER.md can be checked against CI logs.
# A missing file never fails the build: the plant tool then shows "model not installed" and the
# food tool falls back to manual entry. The register records which run produced which hashes.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
MANIFEST="$ROOT/build/assets-manifest.txt"
mkdir -p "$ROOT/build"; : > "$MANIFEST"

fetch_tflite() { # dest, then candidate URLs (direct .tflite or Kaggle tar.gz)
  local dest="$1"; shift
  mkdir -p "$(dirname "$dest")"
  for url in "$@"; do
    echo "trying $url"
    rm -f "$TMP/dl"
    if curl -fsSL --retry 3 -m 300 -o "$TMP/dl" "$url"; then
      if file "$TMP/dl" | grep -qi gzip; then
        mkdir -p "$TMP/x" && rm -rf "$TMP/x"/* && tar -xzf "$TMP/dl" -C "$TMP/x" && f=$(find "$TMP/x" -name "*.tflite" | head -1)
        [ -n "${f:-}" ] && cp "$f" "$dest"
      else
        cp "$TMP/dl" "$dest"
      fi
      if [ -s "$dest" ] && head -c 8 "$dest" | tail -c 4 | grep -q "TFL3"; then
        echo "$(sha256sum "$dest" | cut -d' ' -f1)  $(stat -c%s "$dest")  ${dest#$ROOT/}  $url" | tee -a "$MANIFEST"
        return 0
      fi
      rm -f "$dest"; echo "  not a TFLite flatbuffer"
    fi
  done
  echo "MISSING ${dest#$ROOT/}" | tee -a "$MANIFEST"
}

fetch_tflite "$ROOT/scanner/plant/src/main/assets/models/aiy_plants_v1.tflite" \
  "https://tfhub.dev/google/lite-model/aiy/vision/classifier/plants_V1/3?lite-format=tflite" \
  "https://www.kaggle.com/api/v1/models/google/aiy/tfLite/vision-classifier-plants-v1/1/download" \
  "https://storage.googleapis.com/tfhub-lite-models/google/lite-model/aiy/vision/classifier/plants_V1/3.tflite"

fetch_tflite "$ROOT/scanner/food/src/main/assets/models/aiy_food_v1.tflite" \
  "https://tfhub.dev/google/lite-model/aiy/vision/classifier/food_V1/1?lite-format=tflite" \
  "https://www.kaggle.com/api/v1/models/google/aiy/tfLite/vision-classifier-food-v1/1/download" \
  "https://storage.googleapis.com/tfhub-lite-models/google/lite-model/aiy/vision/classifier/food_V1/1.tflite"

USDA="https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip"
OUT="$ROOT/scanner/food/src/main/assets/packs/food_usda_sr.json"
if curl -fsSL --retry 3 -m 600 -o "$TMP/sr.zip" "$USDA"; then
  echo "$(sha256sum "$TMP/sr.zip" | cut -d' ' -f1)  $(stat -c%s "$TMP/sr.zip")  source-zip  $USDA" | tee -a "$MANIFEST"
  mkdir -p "$TMP/sr" && unzip -q "$TMP/sr.zip" -d "$TMP/sr" && python3 "$ROOT/tools/build_food_pack.py" "$TMP/sr" "$OUT" && \
    echo "$(sha256sum "$OUT" | cut -d' ' -f1)  $(stat -c%s "$OUT")  ${OUT#$ROOT/}  built from source-zip" | tee -a "$MANIFEST"
else
  echo "MISSING ${OUT#$ROOT/}" | tee -a "$MANIFEST"
fi
# Verify the downloadable feature pack the app fetches at runtime still matches its catalog hash.
EFF_URL="https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
EFF_SHA="$(python3 -c 'import json,sys;print([p for p in json.load(open(sys.argv[1]))["packs"] if p["id"]=="detector-efficientdet-lite0"][0]["sha256"])' "$ROOT/core/model-packs/src/main/assets/packs/catalog.json")"
if curl -fsSL --retry 3 -m 300 -o "$TMP/eff.tflite" "$EFF_URL"; then
  GOT="$(sha256sum "$TMP/eff.tflite" | cut -d' ' -f1)"
  if [ "$GOT" = "$EFF_SHA" ]; then echo "$GOT  $(stat -c%s "$TMP/eff.tflite")  pack:detector-efficientdet-lite0 (catalog hash matches)  $EFF_URL" | tee -a "$MANIFEST"
  else echo "MISMATCH pack:detector-efficientdet-lite0 catalog=$EFF_SHA downloaded=$GOT" | tee -a "$MANIFEST"; fi
else
  echo "UNREACHABLE pack:detector-efficientdet-lite0 $EFF_URL" | tee -a "$MANIFEST"
fi
echo "==== assets manifest"; cat "$MANIFEST"
