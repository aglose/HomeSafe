#!/bin/bash
# Works out again where the labels point for every lift already rendered in a directory (the pictures are left as they are).
#   tools/movement-render/relabel.sh <rendered lifts directory>
here="$(cd "$(dirname "$0")" && pwd)"
for folder in "$1"/*/; do
  spec="$(basename "$folder")"
  /Applications/Blender.app/Contents/MacOS/Blender --background --python "$here/lifts.py" -- "${spec%-*}" "${spec##*-}" "${folder%/}" labels > "$1/relabel-$spec.log" 2>&1
  grep -E "Error|Traceback" -B2 -A14 "$1/relabel-$spec.log" | head -30
done
