# Familiar vs. stranger alerts

Frigate's face recognition puts a name on a `person` detection (the event's `sub_label`) when a
registered face matches. HomeSafe uses that in two places.

## Teaching Frigate who's who — Settings › Recognition › Faces

`FaceLibraryScreen` mirrors the classifier labelling screen but has no training step: filing a
face under a person registers it immediately.

- `GET /api/faces` returns folder → image files. The `train` folder holds every face Frigate got a
  good look at, named `<eventId>-<frameEpoch>-<guess>-<score>.webp` (`guess` is `unknown` when
  nobody came close). Registered images are `<name>_<epoch>.webp`. Images are served from
  `/clips/faces/<folder>/<file>` behind the normal session cookie.
- Filing: `POST /api/faces/train/{name}/classify` `{training_file}`. Discarding an attempt or a
  registered image: `POST /api/faces/{folder}/delete` `{ids}` (`folder` = `train` for attempts).
  New person: `POST /api/faces/{name}/create` (answers 2xx with `success:false` and a "Successfully
  created" message — a Frigate quirk, so the client trusts the status code there).
- Person keys are slugs (`FaceLibrary.personKey`), because the folder name is what arrives as a
  sub-label; `subLabelDisplayName` turns it back into "Andrew" / "Sarah's Mom".

Files: `network/FrigateFaceApi.kt`, `domain/model/FaceLibrary.kt`, `domain/repository/FaceRepository.kt`,
`domain/usecase/FaceUseCases.kt`, `data/FaceRepositoryImpl.kt`, `viewmodel/FaceLibraryViewModel.kt`,
`ui/screens/FaceLibraryScreen.kt`, route `FacesRoute` in `SettingsTabNav.kt`.

## "Only strangers" — Settings › Alerts

`AlertSettings.quietFamiliarPeople` (Room column `SettingsEntity.quietFamiliarPeople`, schema 7,
default off). `AlertSettings.notifies(camera, zones, category, recognized)` returns false for a
recognised person while it's on; vehicles with a recognised plate and every other category are
unaffected, and the zone rules still apply to strangers.

Because Frigate names a face a few seconds into a visit, `DetectionAlertService` holds a
still-anonymous, still-present person for up to `RECOGNITION_GRACE_SECONDS` (20 s) before judging
them a stranger, re-reading the event on each poll so a late name suppresses the notification.
People who already left, or who are named on first sight, are decided immediately.

## Server prerequisites

`face_recognition: enabled: true` in Frigate's config (applied 2026-09-05 with `model_size: small`;
the processor is only created at startup, so a `docker restart frigate` was needed). Faces need
about 750 px² on the detect stream to be considered, so the cameras only collect attempts when
someone is reasonably close. The Settings › Alerts toggle is disabled with an explanation when
the server reports face recognition off.

## Push relay

`PushRegistrar` re-registers the device (`POST /devices`, field `quiet_familiar`) whenever the
switch flips or the server address changes. In `relay/relay.py`, `broadcast(..., familiar=True)`
skips phones with that flag when the review item's `sub_labels` name the person, and
`awaiting_recognition()` holds a still-anonymous, still-present person for the same 20 s grace
(only while at least one phone has the flag, so nobody else's push is delayed). Away-mode pushes
ignore the flag: with nobody home, every person is news.

## Not done

- Renaming a person is in the API client but has no UI.
- iOS has no push registration yet, so the relay-side rule only applies to Android phones; the
  in-app poller applies it on every platform.
- Registering a face from an event snapshot (rather than from Frigate's attempts) is not offered.
