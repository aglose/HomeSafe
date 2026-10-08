# Fitness

PercySafe has a fourth app inside it, beside the cameras, [Finance](finance.md) and
[Weather](weather.md): **Fitness**, opened from the drawer behind the top bar's menu button. It
is a training log built round one way of training: a peak set per exercise, written down as a
weight and the most reps done at it, and beaten week on week. It grew out of notes kept that way
in a notes app, and it reads those notes as its starting point.

| Tab | What it shows |
|---|---|
| **Today** | The phase (bulk, cut, maintain) and how long it has run, which workout has waited longest, the week in three numbers, and the workouts as doors: Chest, Back and Legs, then Shoulders and Arms. The one that is up next says what it opens on. Below, the records lately set |
| **Lifts** | Every exercise, shelf by shelf, with its best set, its ladder in miniature and where it stands against its best. Add one by name; bring the notes in |
| **Progress** | The phase and how the lifts are holding up in it, bodyweight with the noise taken out, the week's sets drawn on the body, the last twelve weeks as a calendar, and each lift against its best |

A **workout** lists the exercises on its shelves with last time's peak set and today's target. A
tap opens one into its logger, in place. An **exercise** has a page of its own: the target and
why, the ladder, the logger, strength over time, and an editor behind the pencil.

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
long to rest).

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
  `ExerciseClassifier`. Pure Kotlin.
- `fitness/data`: `FitnessRepositoryImpl` over `FitnessDao` (five tables in the app's Room
  database, schema 19; `data/FitnessEntities.kt`). Everything stays on the device.
- `fitness/FitnessBoard.kt`: `FitnessBoardBuilder` works every screen's state out of the whole
  log in one pass. The log is a few thousand rows at most, so it is simply rebuilt when it changes.
- `fitness/FitnessViewModel.kt`: scoped to the activity and shared with the drawer's card. It
  follows the log once the drawer or the app has been on screen, and holds the open workout's
  rest timer, the record flash and the import draft.
- `fitness/ui`: the screens. `FitnessAppContent` is the whole app from one state, for previews
  (`./gradlew :shared:renderPreviews -Ppreview=Fitness`) and UI tests.

## Tests

- `commonTest/.../fitness/domain`: the parser against notes with every quirk above, the import
  plan, records, the progression table, volume, the bodyweight trend.
- `commonTest/.../fitness`: `FitnessBoardBuilderTest`, and `FitnessViewModelTest` over the real
  repository and an in-memory DAO.
- `uiTest/.../FitnessAppUiTest`: the screens through their test tags.
- `jvmTest/.../FitnessShadersCompileTest` and `androidDeviceTest/.../FitnessShadersTest`: every
  shader compiles, on Skia and on a device's AGSL compiler.
