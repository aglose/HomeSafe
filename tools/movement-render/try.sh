#!/bin/bash
# Renders the first and last frame of each lift named (lift:camera) and lays them out as one picture to look at.
#   tools/movement-render/try.sh <output directory> <name of the sheet> squat:front bench_press:front ...
here="$(cd "$(dirname "$0")" && pwd)"
out="$1"; sheet="$2"; shift 2
stills=()
for spec in "$@"; do
  IFS=: read -r lift view <<< "$spec"
  for frame in 0 24; do
    /Applications/Blender.app/Contents/MacOS/Blender --background --python "$here/lifts.py" -- "$lift" "$view" "$out/$lift-$view" $frame > "$out/blender-$lift-$view.log" 2>&1
    grep -E "Error|Traceback" -B2 -A14 "$out/blender-$lift-$view.log" | head -24
    stills+=("$out/$lift-$view/still-$(printf %02d $frame).webp")
  done
done
/Applications/Blender.app/Contents/MacOS/Blender --background --python "$here/contact.py" -- "$out/$sheet.png" 4 "${stills[@]}" > /dev/null 2>&1
