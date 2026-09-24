# Car recognition

Frigate's `known_cars` classifier names the household's cars ("Andrew's Tesla"), and the app
folds a named car's comings and goings out of the way. On 2026-09-23 it was naming 45-89% of the
cars driving past the Front Yard as one of ours, mostly `andrews_tesla` at 0.98, so strangers were
being folded away with them. Five things were wrong, and this is what was done about each.

| Problem | Fix | Where |
|---|---|---|
| A name let a car skip the zone filter, so any named street car stayed in the feed | A car's name only counts outside a car zone while it is parked | App, `ZoneInference.kt` |
| `none` was the only picture of every other car in the world | Crops of passing street cars are filed into `none` a dozen an hour, and the model retrains daily | Relay, car check |
| The classifier sees a 55-124 px crop of a 640x360 frame | The Front Yard detects on a 1280x720 frame scaled from the 4K stream | Frigate config |
| YOLOv9-t's boxes on a parked car flicker around the 0.7 threshold | YOLOv9-s (COCO mAP 38 -> 47) | Frigate config |
| Nothing checks the classifier | A local vision model looks at driveway cars in the 4K recording and vetoes or corrects the name | Relay, car check + Ollama |

The ceiling stays: nothing that goes by appearance can tell our white Model Y from a neighbour's.
Where a car parks is the signal that can, which is why the app leans on the driveway zone.

## The app

A vehicle's `sub_label` no longer exempts it from the zone filter (`MomentEvent.inZones`) unless
it is parked (`isStill`). A named car that drove down the birds-only street is dropped like an
unnamed one, from the feed, the "In view now" strip and the in-app alerts. A named car parked at
the curb still shows, and so does any car in the driveway.

## The relay's car check

`car_check_forever` in `relay/relay.py` runs every 30 s once `CAR_CLASSIFIER` is set.

- **Street crops into `none`.** Frigate queues a crop for each classification attempt in
  `clips/known_cars/train/` and keeps only the newest 200. A crop is filed into `none` (through
  Frigate's own `categorize` route) when its event is a finished car that entered no car zone,
  travelled at least 0.2 of the frame, and had no car in a car zone within 3 minutes either side.
  That last check stops a household car the tracker lost on its way out from being filed as a
  stranger. The caps are 2 crops per car, `STREET_NONE_PER_HOUR` cars an hour (12) and
  `STREET_NONE_MAX` images in `none` (600), and there is one retrain a day once `RETRAIN_AFTER`
  (60) new cars have gone in. Every decision is kept in the `car_checks` table.
- **Second opinion.** A car in a car zone, finished or in view for 60 s, whose name no person gave
  (score below 1.0), is cut out of the 4K recording at its last path point. That cut, 640 px on
  the long edge, is sent to `qwen3-vl:4b-instruct` in Ollama with a closed JSON schema (colour,
  make, model, body, delivery company). The answer is matched against `HOUSEHOLD_CARS`. If it
  contradicts the classifier's name, the name is swapped for the one household car that fits
  (score 0.9) or cleared. The check never names a car the classifier left unnamed. It writes
  what it saw as the event's description, e.g. "red tesla Model Y suv".
  - Infrared night footage answers colour `unknown`, which rules nothing out.
  - A car missing from `HOUSEHOLD_CARS` is never judged.
  - Plain `qwen3-vl:4b` is the Thinking build; keep the `-instruct` tag.

Check it with:

```bash
ssh frigate 'sudo journalctl -t homesafe-relay --since "1 hour ago" | grep -E "street car|car .* on |retrain|Ollama"'
```

```bash
ssh frigate 'docker exec homesafe-relay python -c "import sqlite3; print(sqlite3.connect(\"/data/relay.db\").execute(\"select kind, verdict, count(*) from car_checks group by 1,2\").fetchall())"'
```

## Deploying

Frigate: detect the Front Yard from go2rtc's copy of the 4K main stream, scaled on the GPU, and
switch to YOLOv9-s. The model was exported with Frigate's documented Docker recipe
(`MODEL_SIZE=s`, `IMG_SIZE=320`) into `~/surveillance/yolo-export/`, and copied to
`config/model_cache/yolov9-s-320.onnx`. `yolo.onnx` (the old `t` model) is still there.

```bash
ssh frigate 'curl -s -X PUT -H "Content-Type: application/json" "http://127.0.0.1:5000/api/config/set?cameras.hikvision_1.ffmpeg.inputs.0.path=rtsp://127.0.0.1:8554/hikvision_1&cameras.hikvision_1.ffmpeg.inputs.0.input_args=preset-rtsp-restream&model.path=/config/model_cache/yolov9-s-320.onnx" -d "{\"requires_restart\":1}"'
```

```bash
ssh frigate 'curl -s -X PUT -H "Content-Type: application/json" "http://127.0.0.1:5000/api/config/set" -d "{\"requires_restart\":1,\"config_data\":{\"cameras\":{\"hikvision_1\":{\"detect\":{\"width\":1280,\"height\":720}}}}}"'
```

```bash
ssh frigate 'docker restart frigate'
```

The backup from before these changes is `config/config.yml.bak-20260923-yolov9s`. To roll back:

```bash
ssh frigate 'cd ~/surveillance/frigate && cp config/config.yml.bak-20260923-yolov9s config/config.yml && docker restart frigate'
```

Relay and Ollama (the first start pulls the Ollama image, then the relay pulls the 3.3 GB model):

```bash
scp relay/relay.py relay/docker-compose.yml andrew@100.99.163.71:/home/andrew/surveillance/relay/
```

```bash
ssh frigate 'cd /home/andrew/surveillance/relay && docker compose up -d --build'
```
