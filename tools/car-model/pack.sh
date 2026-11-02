#!/bin/zsh
# Builds both cars from the base mesh and puts everything the app needs in place.
#
#   tools/car-model/pack.sh [work dir]      (needs Blender, and Python with numpy and pillow)
#
# See docs/car-model.md. Run from the root of the repository. PYTHON may name a Python with those
# two libraries; the sources are fetched into .cache (gitignored).
set -euo pipefail

HERE=tools/car-model
WORK=${1:-$HERE/.cache/work}
WORK=${WORK:A}
CACHE=$HERE/.cache
PYTHON=${PYTHON:-python3}
PHONES=shared/src/commonMain/composeResources/files/car
MAC=features/car3d/src/desktopMain/resources/car
mkdir -p "$WORK/pack" "$CACHE/base/textures" "$PHONES" "$MAC"

# The base mesh: "2021 Tesla Model Y" by tonielpro520, CC BY 4.0, from a public copy with its credit.
BASE=https://raw.githubusercontent.com/ShineTJKU/Ag/0347eb8e199bd0f5982e623887997f1cf07795d8/apps/console-web/public/models/tesla_model_y
if [[ ! -f $CACHE/base/scene.gltf ]]; then
    curl -fsSL -o "$CACHE/base/license.txt" "$BASE/license.txt"
    curl -fsSL -o "$CACHE/base/scene.bin" "$BASE/scene.bin"
    for texture in Carro_Metal_Lanterna_Traseira_baseColor.png Carro_Pneu_baseColor.jpeg; do
        curl -fsSL -o "$CACHE/base/textures/$texture" "$BASE/textures/$texture"
    done
    curl -fsSL -o "$CACHE/base/scene.gltf" "$BASE/scene.gltf"
fi

blender_run() {
    local log
    log=$(mktemp)
    if ! blender -b --factory-startup --python-exit-code 1 --python "$@" > "$log" 2>&1; then
        tail -20 "$log"
        echo "Blender failed: $*" >&2
        exit 1
    fi
    grep -E "triangles" "$log" || true
    rm -f "$log"
}

for car in blue red; do
    blender_run "$HERE/build.py" -- "$CACHE/base/scene.gltf" "$WORK/$car.blend" "$car"
done
for tier in high mobile; do
    blender_run "$HERE/export.py" -- "$WORK/blue.blend" "$WORK/pack" all "$tier" --samples 128
    blender_run "$HERE/export.py" -- "$WORK/red.blend" "$WORK/pack" wheels "$tier" --samples 128
done
blender_run "$HERE/export.py" -- "$WORK/blue.blend" "$WORK/pack" shadow mobile --samples 128

"$PYTHON" -I "$HERE/envmap.py" "$WORK/pack/day_high.env" 256
"$PYTHON" -I "$HERE/envmap.py" "$WORK/pack/day_mobile.env" 128

cp "$WORK"/pack/*_mobile.car "$WORK/pack/day_mobile.env" "$WORK/pack/shadow.bin" "$PHONES/"
cp "$WORK"/pack/*_high.car "$WORK/pack/day_high.env" "$WORK/pack/shadow.bin" "$MAC/"

CAR_PICTURES=1 CAR_DUMP_DIR="$WORK/pictures" CAR_PACK_DIR="$WORK/pack" ./gradlew :car3d:desktopTest --tests '*CarPictureDump*'
"$PYTHON" -I "$HERE/pack_pictures.py" "$WORK/pictures" "$PHONES"
du -sh "$PHONES" "$MAC"
