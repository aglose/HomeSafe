# Phantom people

The detector sometimes sees a person in clutter. On the Front Door camera on 2026-09-29, it made
30 "Person detected" events between 4:23 and 5:14 PM out of whatever sat by the door. Most were
one or two seconds long: each one was the detector's score creeping over the threshold and
falling back. Nobody was there.

Two things deal with this. Both run in the relay (`relay/relay.py`).

## 1. Phantom spots: stop the same phantom at once

On any Moments card or clip row of a person nobody named, tap **Not a person**. The relay
(`POST /events/{id}/not_a_person`) keeps that detection's box as a *phantom spot* on its camera.

After that, a person detection on that camera is treated as the same phantom when all of these hold:

- it has no face name (Frigate's `none`/`unknown` placeholders don't count as names);
- it **stayed put**: every point of its path lies within a quarter of its box's longer side of
  the path's median;
- its box overlaps the spot by IoU ≥ 0.3.

Such a detection is not pushed. It is skipped like a parked car, and it is left out of away-mode
escalation too. The feed hides it as well (`GET /phantoms`, `MomentEvent.isPhantom`), so one tap
clears every re-detection at that spot.

A real person at the same spot still gets through, because they walked there. The rule for
staying put is deliberately stricter than a car's `is_still`. Up close, a person's box is half the
frame, and someone climbing the steps moves less than that.

While an alert is still open and young (under 10 minutes), it waits instead of being skipped.
That gives a person who just appeared time to show that they move. Undo
(`DELETE /events/{id}/not_a_person`) removes the spot.

## 2. Person check: learn what isn't a person

A spot only catches the phantom it was shown, where it was shown. The relay also grows the
dataset of a Frigate object classifier that runs on every tracked person. It works the way
`known_cars` does on cars, with two classes:

| Class | Where its examples come from |
|---|---|
| `none` | Every detection marked **Not a person**. The relay uses Frigate's queued crop when it has one; otherwise it cuts the crop from the recording, framed exactly as Frigate frames one. After that, every queued crop of a phantom re-detection is also filed here. |
| `person` | Queued crops of people Frigate put a face's name to. |

Queued crops are filed at most `PERSON_FILED_PER_HOUR` (12) an hour, and each class is capped at
`PERSON_CLASS_MAX` (400). The model is retrained at most once a day, once `PERSON_RETRAIN_AFTER`
(20) new examples have gone in since the last retrain.

### Setting it up on the box

1. Add the model to Frigate's `config.yml`. This follows `known_cars`; check the key names
   against Frigate 0.17's object-classification docs before applying it:

   ```yaml
   classification:
     custom:
       person_check:
         enabled: true
         name: person_check
         threshold: 0.8
         object_config:
           objects: [person]
           classification_type: attribute   # not sub_label: a face's name lives there
   ```

2. Restart Frigate. It creates `clips/person_check/` and starts queueing person crops.
3. The relay already has `PERSON_CLASSIFIER: person_check` (`relay/docker-compose.yml`). It stays
   idle until that folder exists.
4. Mark a few phantoms from the app. The first retrain happens once both classes have examples.

### Not done yet

- **The relay doesn't act on the classifier's verdict.** Once the model is trained, check where
  Frigate 0.17 stores an `attribute` classification on the event. Then add that verdict to
  `phantom_verdict` as a third test, alongside the spot and staying put.
- **An example filed by mistake stays in `none`** when a mark is undone. Move it from the
  labelling screen.
- **The app's own poller** (`DetectionAlertService`, used only on phones the relay doesn't push to)
  doesn't know about phantom spots.
