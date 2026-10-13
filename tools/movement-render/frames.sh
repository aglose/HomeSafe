#!/bin/bash
# Renders chosen frames of one lift from one camera and lays them out in a row to look at.
#   tools/movement-render/frames.sh <output directory> <lift> <camera> <frame> [frame ...]
here="$(cd "$(dirname "$0")" && pwd)"
out="$1"; lift="$2"; view="$3"; shift 3
mkdir -p "$out"
stills=()
for frame in "$@"; do
  /Applications/Blender.app/Contents/MacOS/Blender --background --python "$here/lifts.py" -- "$lift" "$view" "$out/$lift-$view" $frame > "$out/blender-$lift-$view.log" 2>&1
  grep -E "Error|Traceback" -B2 -A14 "$out/blender-$lift-$view.log" | head -24
  stills+=("$out/$lift-$view/still-$(printf %02d $frame).webp")
done
/Applications/Blender.app/Contents/MacOS/Blender --background --python "$here/contact.py" -- "$out/$lift-$view.png" $# "${stills[@]}" > /dev/null 2>&1
