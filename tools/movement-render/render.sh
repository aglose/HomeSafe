#!/bin/bash
# Renders lifts with Blender, one after another: each argument is lift:camera or lift:camera:frame.
#   tools/movement-render/render.sh <output directory> squat:front incline_press:front:32
here="$(cd "$(dirname "$0")" && pwd)"
out="$1"; shift
mkdir -p "$out"
for spec in "$@"; do
  IFS=: read -r lift view frame <<< "$spec"
  /Applications/Blender.app/Contents/MacOS/Blender --background --python "$here/lifts.py" -- "$lift" "$view" "$out/$lift-$view" $frame > "$out/blender-$lift-$view.log" 2>&1
  grep -E "Error|Traceback" -B2 -A14 "$out/blender-$lift-$view.log" | head -30
done
