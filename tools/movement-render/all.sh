#!/bin/bash
# Renders every lift the app shows (and the squat's second view), skipping those already done; run again to fill in any that failed.
#   tools/movement-render/all.sh <output directory>
here="$(cd "$(dirname "$0")" && pwd)"
out="$1"
for spec in squat:front squat:back incline_press:front bench_press:front overhead_press:front chest_fly:front rear_delt_fly:back lat_pulldown:back pull_up:back seated_row:back lateral_raise:front front_raise:front shrug:back biceps_curl:front triceps_pushdown:back dip:back leg_press:front leg_extension:front leg_curl:back calf_raise:back hip_hinge:back hip_abduction:back hip_adduction:front crunch:front; do
  IFS=: read -r lift view <<< "$spec"
  [ -f "$out/$lift-$view/preview.webp" ] || "$here/render.sh" "$out" "$spec"
done
