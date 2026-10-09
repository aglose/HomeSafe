# Fitness

PercySafe has a fourth app inside it, beside the cameras, [Finance](finance.md) and
[Weather](weather.md): **Fitness**, opened from the drawer behind the top bar's menu button. It
is a training log built round one way of training: a peak set per exercise, written down as a
weight and the most reps done at it, and beaten week on week. It grew out of notes kept that way
in a notes app, and it reads those notes as its starting point.

| Tab | What it shows |
|---|---|
| **Today** | The phase (bulk, cut, maintain) and how long it has run, which workout has waited longest, the week in three numbers, and the workouts as doors: Chest, Back and Legs, then Shoulders and Arms. The one that is up next says what it opens on. Below, the records lately set |
| **Lifts** | Every exercise, shelf by shelf, with its best set, its ladder in miniature and where it stands against its best. Add one by name; bring the notes in; [send a copy of the log](#moving-the-log) to another install |
| **Progress** | The phase and how the lifts are holding up in it, bodyweight with the noise taken out, the week's sets drawn on the body, the last twelve weeks as a calendar, and each lift against its best |

A **workout** lists the exercises on its shelves with last time's peak set and today's target. A
tap opens one into its logger, in place. An **exercise** has a page of its own: the target and
why, the ladder, the logger, strength over time, and an editor behind the pencil. With a
heart-rate sensor connected (Android), the workout also shows the [heart rate](#heart-rate) live.

## The model: a ladder

The notes were kept as a list per exercise, one line a weight:

```
Hack squat
- 290lbs - 12 reps
- 320lbs (3plates+25lbs)- 10 reps
```

That list is the model. An exercise's **ladder** (`Strength.ladder`) has a rung for each weight
ever used, with the most reps done at it; beating a rung, or adding one, is what progress is.
Everything else is read off the sets:

- **Sets** carry a weight in the exercise's own unit (`LoadKind`: pounds, pounds a hand, plates a
  side kept as total pounds, a machine's pin number, or bodyweight plus what was hung on), the
  reps, and when. A set from the notes with no date is a *benchmark* (`epochSeconds` 0): it
  counts toward the ladder and the all-time bests, and belongs to no week and no phase.
- **One scale** for comparing sets of different weights and reps: the Epley estimate,
  `load × (1 + reps / 30)`. It ranks sets within an exercise and can be turned round ("how many
  reps at this weight would beat that?"). It is never shown as a promise of a one-rep max.
- **Phases** are lines drawn through the log: a bulk, a cut or maintenance starts on a day and
  runs until the next one does.

## What to aim for

`Progression.nextTarget` turns an exercise's history into one target for its peak set, with a
line saying why. It is double progression: work the reps up through the exercise's band at one
weight, then add weight and start lower in the band.

| Situation | Target |
|---|---|
| Only the notes to go on | The heaviest rung whose reps were still inside the band: find the level again |
| Normal | One more rep than last session's best set |
| Top of the rep band reached | The next weight up (a rung already on the ladder if one is near, else the usual jump), at the reps that would beat last time |
| On a cut | Match last time. If the reps have fallen under the band, a step down in weight |
| Under 97% of the all-time best, not cutting | Rebuilding: two reps a session, since strength that was there returns faster than it was built |
| 18 days or more since it was trained | Ease in: 90%, 82%, 72% or 65% of the last numbers by how long it has been |

While a workout is open the target does not move: the sets being logged are the attempt at it.

The band and the usual jump are the exercise's own. On import they are read off its ladder (the
reps usually reached before moving up; the commonest gap between weights) and otherwise guessed
from its name; either can be changed on the exercise's page.

Why these rules:

- Adding reps and adding load build muscle about equally (Plotkin et al. 2022, PeerJ), and reps
  from about 5 to 30 all work when sets are taken near failure (Schoenfeld et al. 2017, 2021).
- In a deficit strength can be kept even as gaining muscle stalls (Murphy & Koehler 2022), and
  what keeps it is holding the load (Spiering et al. 2021). So a cut's target is to match.
- Lost strength comes back quicker than it was first gained: ten weeks off was regained in about
  five (Halonen et al. 2024). The ease-in steps and the rebuild pace are a coach's rules of thumb
  consistent with that, not a measured curve.

## Records, and cuts

`Strength.record` judges a set against everything before it on the same exercise:

- **All-time**: heavier than anything ever lifted, or more reps than were ever done at that weight
  or a heavier one (a rung beaten). The notes' numbers count as what there was to beat, and can't
  themselves be records.
- **Best of this cut / bulk**: short of all-time, the best set since the phase began. On a cut the
  all-time numbers are out of reach for a while; this is the one worth chasing.

A first set of an exercise, and a tie, are neither. A record flashes across the screen when the
set is logged, gold for all-time and the phase's colour otherwise.

Beside records, every lift has a **standing**: last session's best against the all-time best. It
is what the Progress tab's gauge averages ("93% of your best") and what the list at its foot is
sorted by, so that after a cut the lifts with the most ground to make up come first.

## The week on the body

`Volume.weekly` counts the last seven days' logged sets by muscle: a set counts in full for the
muscle its exercise is for and half for the ones that help, the counting that tracked growth best
across the studies pooled by Pelland et al. Each muscle has a band to land in for one hard session
a week (8 to 12 sets for chest, back and quads; fewer for smaller muscles). Only sets that were
logged are counted, so someone who writes down one peak set per exercise sees the shape of their
week here, not their true set count.

Bodyweight is tracked as a trend (`BodyweightTrend`): an exponentially smoothed weight, with a
weekly rate of change read against the phase. A cut is paced at 0.5 to 1% of bodyweight a week
(Helms et al. 2014), a bulk at 0.25 to 0.5% (Iraki et al. 2019).

## Heart rate

A workout can show the heart rate live, with the zone it is in, from a Bluetooth heart-rate
sensor. It was built for a Google Fitbit Air, a band with no screen and no API of its own, which
can be made to behave like a chest strap; any sensor that speaks the standard Bluetooth Heart
Rate Service (0x180D, notifying on Heart Rate Measurement, 0x2A37) works the same way. **Android
only for now**: on iOS, the desktop and the web the port answers "unsupported" and none of this
is shown.

### Setting it up

1. In the Google Health app, open the band's **Connections** and set **Share heart rate** to
   **Always visible**. With sharing off the band doesn't advertise at all and can't be found.
2. In PercySafe: start a workout and tap the heart button beside the counts (or **Progress →
   Heart rate and zones**), then **Find my sensor**. Android asks for the *Nearby devices*
   permission here, at the tap, and not before. Pick the band from the list; it is remembered.
   The first time, Google Health may put up its own request to approve the sharing, and Android
   may ask to pair: say yes to both.
3. On the same page, give the zones a maximum heart rate, or an age to estimate one from.

From then on the band is found by itself whenever the fitness app is on screen. It is looked for
by the address it was chosen at, and after six seconds of not hearing that, by its name: a
wearable may change the address it advertises from. Two bands of one name in the same room can't
be told apart that way; **Forget this sensor** and choosing again settles it.

### Zones

Five zones of ten percent each from half of the maximum heart rate: 50–60, 60–70, 70–80, 80–90
and 90–100% (`HeartZones.FLOORS`, the one place the thresholds are kept). This is the five-zone
convention heart-rate monitors use (Polar's "sport zones"), chosen so the number means what it
means on other equipment. It is a convention, not a measurement of the person: ACSM's intensity
classes draw slightly different lines (Garber et al. 2011), and real thresholds are found by
testing.

| The maximum | |
|---|---|
| Entered | Used as it is |
| Estimated from age | `208 − 0.7 × age` (Tanaka, Monahan & Seals 2001). A population average: about ten beats out either way for a given person |
| With a resting rate | The same percentages of the *reserve* between resting and maximum, added back on (Karvonen et al. 1957), which lifts the easy zones most |

The age is whatever is entered on the page; nothing about the lifter is built in.

### On a workout

- **The panel** over the exercise list: a heart beating in time with the wearer's, the beats a
  minute in the logger's big numerals, and the zone as a block of its colour (grey, blue, green,
  orange, red) with its number in it, sized to be read from a phone on the bench. Under them the
  five zones as a strip with the time spent in each.
- **A change of zone** is said once: the panel's edge thickens in the new zone's colour, a line
  says "Up to zone 4 · Hard" for four seconds, and the phone buzzes (long for up, short for
  down). No sound. A zone only counts once the readings have stayed in it for four seconds
  (`ZoneTracker`), so a heart rate sitting on a line doesn't buzz at every beat.
- **During a rest** the timer shows the heart coming back down: the highest it read since the
  set, what it reads now, and the zone it has fallen to.
- **Afterwards**, Today shows the last workout's heart: average, peak, and time in each zone.
  Only the totals are kept (one row a workout); the readings themselves are not stored.

Readings are approximate. An optical sensor on the wrist can read wrong while a bar is gripped
hard, so the numbers are a guide and nothing in the progression rules uses them. A reading of 0,
one the band marks as off the skin, or one outside 30–240 is shown as no reading.

### When it stops

**The heart rate is followed only while the fitness app is on screen.** There is no foreground
service: when the phone is locked, the screen times out, or another app comes in front, the link
is let go, and it is found again (a few seconds) on coming back. Zone alerts therefore only
happen with the app in front. Time in zone counts only time the sensor was really reporting: a
gap of more than five seconds between readings adds nothing, so a workout done mostly with the
screen off will show a short "recorded" time, not a wrong one.

If the link drops mid-workout (out of range, band off) the panel says so and the app keeps
looking, backing off from two seconds to thirty. A link that has said nothing for twelve seconds
is treated as dropped without waiting for Android to notice.

## Bringing notes in

**Lifts → Bring in notes**, or share a note to PercySafe from a notes app (Android: the share
sheet's *Import workout notes*). The page shows what it reads as it is typed, and saves nothing
until Import is pressed. Importing the same note twice adds nothing twice, and an exercise that
has been edited in the app is left as it is.

`NotesParser` takes notes as they are really kept:

| Written | Read as |
|---|---|
| `- 290lbs - 12 reps` | 290 lb × 12 |
| `- 44lbs x 2 - 20 reps` | a pair of 44 lb dumbbells × 20 |
| `- 4 plates + 25lbs - 13 reps` | (4 × 45 + 25) × 2 = 410 lb, shown as "4 plates + 25" |
| `- 14 - 29 reps` | pin 14 × 29 |
| `- 29 reps - 55 lbs` | 55 lb × 29 |
| `- 15 reps`, or just `15` | bodyweight × 15 |
| `- 30lbs - 11 reps - bodyweight 175lb` | bodyweight + 30 lb × 11, at 175 lb |
| `- 230lbs - 8 pause full rom` | 230 lb × 8, with the remark kept |
| `- 110lbs -` | a rung not yet done (it sets the usual jump, and is not a set) |
| `25 all time` / `19 recently` | a benchmark of 25, and 19 stamped with today as where things stand now |
| `Lat pulldown - 225lbs 4 reps` | a whole set on one line |
| `Tris`, `Legs`, `Chest` on a line alone | the shelf for what follows |
| `Philly Sept 2021` | the date for what follows (the middle of that month) |
| `Other machine`, under an exercise | that exercise, on the other machine |

A line it can make nothing of is listed on the page instead of guessed at. `ExerciseClassifier`
fills in the rest of an exercise from its name (what it is done on, which muscles it works, how
long to rest). A note pasted without its title has no heading to say which shelf it is for, so
each exercise is placed by its own name; the chips under the text box say it outright. Where
the guess is wrong, the pencil on an exercise (in a workout, or on its page) moves it to another
shelf and sets the muscle it is mainly for. With no heading, an exercise the app already has
under that name is taken to be the same one wherever it has been filed since, so importing
again never makes a second copy of something that was moved.

## Moving the log

Everything stays on the device, so a second install of the app starts empty: another phone, or
the release build beside the debug one (they are two apps to Android, each with its own
database, and nothing can be copied into a release build's from outside). The log is carried
across as text.

**Lifts → Send a copy of your log** (Android) opens the share sheet on the whole log. Choosing
the other install there (its *Import workout notes*) opens its import page, the door notes come
in by, which sees that this is a copy and not notes and shows what it would add: a count each
of new exercises, sets, workouts, phases and weigh-ins. Nothing is saved until Import is
pressed. The same text can be sent anywhere else text goes (a file in Drive, a note) and pasted
into **Bring in notes** later, on any platform.

What a copy brings (`LogCopies.merge`):

- **Only what isn't there yet.** A set is the same set when its exercise, weight, reps and time
  are; a workout when its focus and its start are; a phase when its kind and its start are; a
  weigh-in when it is the same day's. So the same copy brought in twice adds nothing twice, and
  a copy brought into a log already in use leaves what was there.
- **Ids are the receiving log's own.** The copy's workouts get new ones, and their sets and
  heart summaries follow them.
- **Exercises are set up the way the copy has them** (shelf, rep band, jump, rest, note), since
  the copy is the log as its owner last arranged it.
- **Heart-rate settings fill in what is missing** and leave what is there: the zones' numbers,
  and the sensor that was chosen.

The text (`LogCopyText`) is JSON that opens with `"percysafeTrainingLog": 1`, the format's
version. A set is written inside its exercise under one-letter names, about thirty characters
each, because the whole of it has to fit in what one Android app can hand another
(`FitnessShares.MAX_LENGTH`, 200,000 characters): a log of a few hundred sets comes to about a
tenth of that. Enums travel by name and fall back as they do from the database. Text
that says it is a copy but can't be read (cut short, or from a later format) is said to be
unreadable, and is never read as notes.

## How it's drawn

Four runtime shaders (`fitness/ui/shader/FitnessShaders.kt`), AGSL on Android and the same source
as a Skia runtime effect elsewhere:

1. **Forge**: the glow behind every screen. Noise warped through itself so it rolls upward like
   heat off coals, with sparks. It takes the colours of what is on screen (the phase's, or the
   workout's while one is open) and burns harder during a workout.
2. **Fibre**: a workout's door. Muscle fibre running slantwise in the workout's two colours, with
   a wave of contraction travelling down it, quicker on the door that is up next.
3. **Ring**: a gauge filled clockwise in running plasma, with a bead at its head. The rest timer
   and the "of your best" gauge.
4. **Burst**: a record going off. A flash, a ring of force, rays and sparks, once.

Where a shader can't compile, each has a plain gradient that says the same. The rest is Compose:
the ladder's bars rising in turn with the target rung outlined and breathing, the body lighting up
muscle by muscle, the numbers in the logger rolling to their new value.

## How it's built

- `fitness/domain`: the models, `Strength`, `Progression`, `Volume`, `NotesParser`, `NotesImport`,
  `ExerciseClassifier`, `LogCopy.kt` (a copy of the log, as text and merged into another); and for
  the heart rate `HeartRate.kt` (zones, the measurement's bytes,
  settling into a zone, time in zone) and `HeartRateSensor.kt` (the sensor's states and the two
  interfaces below). Pure Kotlin.
- `fitness/data`: `FitnessRepositoryImpl` over `FitnessDao` (seven tables in the app's Room
  database: five from schema 19, the heart-rate settings and each workout's time in zone from
  schema 20; `data/FitnessEntities.kt`). Everything stays on the device.
- The heart-rate sensor is in two halves. `HeartRateLink` is the platform's radio and carries
  only bytes (`createHeartRateLink`; Android scans for the Heart Rate Service and holds a GATT
  link, the others are stubs). `HeartRateMonitorImpl` is everything else, in common code: which
  advertisement is the chosen sensor, reading the measurement, and trying again when a link
  drops, goes quiet or never comes up. So all of that is tested against a fake radio.
- `fitness/FitnessBoard.kt`: `FitnessBoardBuilder` works every screen's state out of the whole
  log in one pass. The log is a few thousand rows at most, so it is simply rebuilt when it changes.
- `fitness/FitnessViewModel.kt`: scoped to the activity and shared with the drawer's card. It
  follows the log once the drawer or the app has been on screen, and holds the open workout's
  rest timer, the record flash and the import draft. The heart rate is a second state beside
  the first (`heart`), since it alone changes every second.
- `fitness/ui`: the screens. `FitnessAppContent` is the whole app from one state, for previews
  (`./gradlew :shared:renderPreviews -Ppreview=Fitness`) and UI tests.

## Tests

- `commonTest/.../fitness/domain`: the parser against notes with every quirk above, the import
  plan, records, the progression table, volume, the bodyweight trend, and `LogCopyTest` (a copy
  as text and back, and what it adds to an empty log, the same log and one in use).
- `commonTest/.../fitness`: `FitnessBoardBuilderTest`, and `FitnessViewModelTest` over the real
  repository and an in-memory DAO.
- Heart rate: `HeartRateTest` (zones, the measurement's bytes, settling, time in zone),
  `HeartRateMonitorImplTest` (the connection's whole life against a fake radio, on virtual time),
  `FitnessHeartViewModelTest`, and `jvmTest/.../FitnessHeartMigrationTest`, which takes a real
  SQLite file from schema 19 to 20 and checks the training log is still in it. The Android radio
  itself (`HeartRateLink.android.kt`) has no test: it needs a band.
- `uiTest/.../FitnessAppUiTest`: the screens through their test tags.
- `jvmTest/.../FitnessShadersCompileTest` and `androidDeviceTest/.../FitnessShadersTest`: every
  shader compiles, on Skia and on a device's AGSL compiler.
