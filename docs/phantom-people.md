# Phantom people

The detector sometimes sees a person in clutter. On the Front Door camera on 2026-09-29, it made
30 "Person detected" events between 4:23 and 5:14 PM out of whatever sat by the door. Most were
one or two seconds long: each one was the detector's score creeping over the threshold and
falling back. Nobody was there.

Saying so takes one tap, in any of three places:

- the **Not a person** button on an Android notification about a person nobody named. It works
  without opening the app, then the alert is replaced by a quiet "Marked not a person" notification
  with **Undo** (it clears itself after ten minutes);
- the **Was anyone there?** prompt on the camera screen a notification opens;
- the **Not a person** pill on any Moments card or clip row of a person nobody named.

All three make the same mark (`POST /events/{id}/not_a_person` on the relay). The app uses its
Frigate session. The notification button uses the install's device secret, because it may wake
the app with no session at all. Three things come of a mark, all in the relay (`relay/relay.py`).

## 1. Phantom spots: stop the same phantom at once

The relay keeps the marked detection's box as a *phantom spot* on its camera. After that, a
person detection on that camera is treated as the same phantom when all of these hold:

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

## 2. Person check: a model that learns what isn't a person

A spot only catches the phantom it was shown, where it was shown. To get better over time, the
relay grows the dataset of a Frigate object classifier that runs on every tracked person. It
works the way `known_cars` does on cars, with two classes:

| Class | Where its examples come from |
|---|---|
| `phantom` | Every detection marked **Not a person**. The relay uses Frigate's queued crop when it has one; otherwise it cuts the crop from the recording, framed exactly as Frigate frames one. After that, every queued crop of a re-detection at a phantom spot is also filed here. |
| `person` | Queued crops of people Frigate put a face's name to, and of people whose path crossed at least a fifth of the frame (`PERSON_WALKED`). Clutter doesn't walk, and the walkers keep the class from being only the household's faces at the door. |

Queued crops are filed at most `PERSON_FILED_PER_HOUR` (12) an hour, and each class is capped at
`PERSON_CLASS_MAX` (400). The model is retrained at most once a day, once `PERSON_RETRAIN_AFTER`
(20) new examples have gone in since the last retrain.

### Why `phantom` and not `none`

Frigate drops a `none` verdict before it reaches the event (`get_weighted_score` in
`custom_classification.py`), so a `none` class would have left the relay unable to see the model
say "not a person". With a class of its own, the verdict lands in the event's data as
`data.person_check = "phantom"` and `data.person_check_score`, and Frigate writes it there while
the event is still open.

### Acting on the verdict

A person who **stayed put**, has no face name, and is called `phantom` with a score of at least
`PERSON_PHANTOM_MIN_SCORE` (0.9) is a phantom anywhere on any camera, with no spot needed. It is
skipped and waits while young, exactly as a spot match is. Someone who walks is always pushed,
whatever the model says.

The relay never files a detection that *only* the model called a phantom back into `phantom`.
That would teach the model its own guesses. Only marks and spot matches go in.

### Undo

Undo removes the spot and takes the example back out of the dataset, into
`clips/person_check/undone/`, kept rather than deleted. If a retrain happened in between, the
model has already seen it, and the next retrain forgets it.

### Setting it up on the box

1. Add the model to Frigate's `config.yml`. It follows `known_cars`:

   ```yaml
   classification:
     custom:
       person_check:
         threshold: 0.8
         object_config:
           objects: [person]
           classification_type: attribute   # not sub_label: a face's name lives there
   ```

2. Restart Frigate. It creates `clips/person_check/` and starts queueing person crops into
   `train/`. Until the first training it judges nothing.
3. The relay already has `PERSON_CLASSIFIER: person_check` (`relay/docker-compose.yml`). It stays
   idle until that folder exists.
4. Mark phantoms as they come. Walkers fill `person` on their own. The first retrain happens
   once both classes have examples and 20 have gone in. Expect a few days of marks before the
   verdict counts for much. Until then the spots do the work.

## 3. What would improve the detector itself

Everything above sits *after* the detector (YOLOv9-s at 320 px on the box). It filters the
detector's mistakes; it doesn't stop the detector making them. Only retraining the detector on
these cameras' own false positives does that. With Frigate, that means
**[Frigate+](https://frigate.video/plus/)**: a paid service that fine-tunes a model on frames you
submit and annotate. Frigate's own `PUT /api/events/{id}/false_positive` submits there, so the
relay could forward each mark. It needs a `PLUS_API_KEY`, snapshots turned on for the cameras
(they are off today), and a Frigate+ model in place of YOLOv9-s. None of that is set up.

## Not done yet

- **The feed doesn't hide what only the model judged a phantom.** The app mirrors the spot rule
  (`MomentEvent.isPhantom`) but doesn't read the classifier's attribute. It would need the event's
  `data.person_check` in `MomentEvent` and its Room entity.
- **iOS has no notification button.** A tap opens the detection, which offers the mark.
- **The app's own poller** (`DetectionAlertService`, used only on phones the relay doesn't push to)
  doesn't know about phantom spots or the model.
