#!/usr/bin/env bash
# Export a .drawio file to .drawio.png using the draw.io export API
# Usage: ./drawio-to-png.sh <input.drawio> [output.drawio.png]

set -euo pipefail

INPUT="${1:?Usage: $0 <input.drawio> [output.drawio.png]}"
OUTPUT="${2:-${INPUT%.drawio}.drawio.png}"

if [[ ! -f "$INPUT" ]]; then
  echo "Error: Input file not found: $INPUT"
  exit 1
fi

XML_CONTENT=$(cat "$INPUT")

# Build JSON payload
PAYLOAD=$(python3 -c "
import json, sys
xml = sys.stdin.read()
print(json.dumps({
    'xml': xml,
    'format': 'png',
    'w': 0,
    'h': 0,
    'border': 20,
    'bg': '#ffffff',
    'scale': 2,
    'extras': '{\"retina\":true}'
}))
" <<< "$XML_CONTENT")

echo "Exporting ${INPUT} -> ${OUTPUT}..."

if ! HTTP_CODE=$(curl -sS -X POST \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" \
  "https://convert.diagrams.net/node/export" \
  -o "$OUTPUT" \
  -w '%{http_code}'); then
  echo "Error: Request to the export service failed"
  rm -f "$OUTPUT"
  exit 1
fi

if [[ -f "$OUTPUT" ]]; then
  SIZE=$(stat -c%s "$OUTPUT" 2>/dev/null || stat -f%z "$OUTPUT" 2>/dev/null)
  # A PNG file starts with the 8-byte signature 89 50 4E 47 0D 0A 1A 0A
  SIGNATURE=$(head -c 8 "$OUTPUT" | od -An -tx1 | tr -d ' \n')
  if [[ "$HTTP_CODE" != "200" ]]; then
    echo "Error: Export service returned HTTP ${HTTP_CODE} (${SIZE} bytes)"
    head -c 500 "$OUTPUT"; echo
    rm -f "$OUTPUT"
    exit 1
  elif [[ "$SIGNATURE" != "89504e470d0a1a0a" ]]; then
    echo "Error: Export service response is not a PNG (${SIZE} bytes)"
    head -c 500 "$OUTPUT"; echo
    rm -f "$OUTPUT"
    exit 1
  elif [[ "$SIZE" -gt 100 ]]; then
    echo "PNG saved: ${OUTPUT} (${SIZE} bytes)"
    # Verify it's actually a PNG
    FILE_TYPE=$(file -b "$OUTPUT" | head -1)
    echo "File type: ${FILE_TYPE}"
  else
    echo "Error: Output file too small (${SIZE} bytes), export likely failed"
    cat "$OUTPUT"
    rm -f "$OUTPUT"
    exit 1
  fi
else
  echo "Error: Output file not created"
  exit 1
fi
