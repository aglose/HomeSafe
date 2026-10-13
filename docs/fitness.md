# Fitness

PercySafe has a fourth app inside it, beside the cameras, [Finance](finance.md) and
[Weather](weather.md): **Fitness**, opened from the drawer behind the top bar's menu button. It
is a training log built round one way of training: a peak set per exercise, written down as a
weight and the most reps done at it, and beaten week on week. It grew out of notes kept that way
in a notes app, and it reads those notes as its starting point.

| Tab | What it shows |
|---|---|
| **Today** | The phase (bulk, cut, maintain) and how long it has run, which workout has waited longest, [the body today](#the-body-and-runs) (a readiness score, steps, last night's sleep), the week in three numbers, and the workouts as doors: Chest, Back and Legs, then Shoulders and Arms, and a door to the [runs](#runs). The one that is up next says what it opens on. Below, the records lately set |
| **Lifts** | Every exercise, shelf by shelf, with its best set, its ladder in miniature and where it stands against its best. Add one by name; bring the notes in; [send a copy of the log](#moving-the-log) to another install |
| **History** | [A month as a calendar](#looking-back), each day something was trained lit in that workout's colour, what the month came to, and the month's workouts with what each came to. A workout opens onto its own page, where it can be deleted |
| **Progress** | The phase and how the lifts are holding up in it, bodyweight with the noise taken out, the week's sets drawn on the body, the last twelve weeks as a calendar, and each lift against its best |

A **workout** lists the exercises on its shelves with last time's peak set and today's target,
each with a still of [the movement](#the-figure-on-an-exercise) at the start of its row. A
tap opens one into its logger, in place, with the figure performing it above. At its foot it is
finished, which keeps it, or [cancelled](#cancelling-and-deleting), which throws it away. An **exercise** has a page of its own: the target and
why, the ladder, the logger, strength over time, and an editor behind the pencil. With a
heart-rate sensor connected (Android), the workout also shows the [heart rate](#heart-rate) live.

What a band records away from the app (steps, sleep, runs) is read from [Health Connect](#the-body-and-runs)
(Android), and is the one part of the app that isn't the app's own log.

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

## Looking back

**History** is the log as sessions (`WorkoutSession`, worked out by `FitnessBoardBuilder` with
everything else): each workout with the sets logged in it, and, for sets logged from an
exercise's page with no workout open, one entry a day ("Outside a workout"). The notes' own
numbers are no session: they are what there was before the log.

- **The month** is a grid of days in weeks that begin on Monday. A day something was trained is
  filled with its workout's colour (the legend under the grid names the ones in view), a day with
  more than one session carries a dot for each (up to three, all a square has room for), and
  today is ringed. The arrows walk back as far
  as the log goes and forward no further than now; off the present month, **This month** comes
  back to it. A workout is filed under the local day it began, whenever it ended, and that day and
  its time of day are read by the offset from UTC at that moment, not today's: a workout from
  July keeps its day and its hour when it is looked back on after the clocks have changed.
- **Under the grid**, what the month came to: workouts, sets, hours and records. A day's loose
  sets are in the sets and the records, but aren't counted as a workout.
- **The list** is the month's sessions, the latest first: what it was, when it began, how long it
  ran, and its sets, exercises and volume in a line, with a trophy and a count where records were
  set. A tap on a lit day narrows the list to that day; **Whole month** brings the rest back.
- **A session's page** has the same numbers larger (time, sets, reps, volume, exercises,
  records), its heart if a sensor was on for it (average, peak, time in each zone), and every set
  under its exercise in the order they were logged, the records among them marked. An exercise's
  name opens its own page. The workout in progress isn't looked back on: its entry in the list
  says so, and a tap carries on with it.

**Volume** is every rep times its weight (`Strength.poundsMoved`): a pair of dumbbells counts
both hands, a bodyweight movement the body and what was hung from it. A machine counted by its
pin number isn't pounds and is left out, and the page says so where that applies. Like the
week's sets it is only what was logged, so for someone who writes down one peak set per exercise
it is the shape of a session, not its whole tonnage.

### Cancelling and deleting

- **Cancel workout**, under Finish on a workout in progress, throws it away as though it had
  never been started: the sets logged in it go, its heart goes, and a record one of those sets
  was is no record any more. It takes a second tap, which says how many sets would go. A workout
  with nothing logged in it yet has only the one button, and closing it leaves no trace.
- **Delete workout**, at the foot of a session's page, does the same to a finished one, again on
  a second tap. For an "Outside a workout" entry it deletes that day's loose sets.

Either is one transaction in the database (`FitnessDao.removeWorkout`: the sets, the heart, the
workout), so one cut off part-way leaves the workout whole. There is no undo. A workout that was
[sent as a copy](#moving-the-log) beforehand comes back if that copy is brought in again, since
a copy adds whatever the log hasn't got.

## Heart rate

A workout can show the heart rate live, with the zone it is in, from a Bluetooth heart-rate
sensor. It was built for a Google Fitbit Air, a band with no screen and nothing an app can ask
it for directly, which can be made to behave like a chest strap; any sensor that speaks the standard Bluetooth Heart
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
- **Afterwards**, Today shows the last workout's heart: average, peak, and time in each zone;
  and any workout's is on its page in History. Only the totals are kept (one row a workout); the
  readings themselves are not stored.

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

## The body, and runs

What the band records when the app isn't looking, it gives to the Google Health app, and Google
Health can write it into **Health Connect**, Android's store of health records on the phone.
The fitness app reads it from there. **Android only**: on iOS, the desktop and the web the port
answers "unsupported" and none of this is shown. It only reads. Nothing read leaves the phone
(not to the home server, not anywhere), and nothing read is kept by the app: Health Connect is
asked again each time the fitness app comes on screen, at most once a minute.

### Setting it up

1. In the Google Health app: **Connections → Partner apps → Health Connect**, switched on, with
   the kinds to share ticked. The band syncs through the phone about every quarter of an hour.
2. In PercySafe: the **Body** card at the top of Today has **Connect Health Connect**. Android
   puts up its sheet here, at the tap, with each kind of record to allow or refuse by itself:
   steps, sleep, heart rate, resting heart rate, heart rate variability, exercise, distance,
   calories and elevation, and (where the phone offers it) history older than thirty days.
3. After two refusals Android stops showing the sheet. The card says so and offers **Open Health
   Connect**, which is the app's page in Health Connect's own settings; the same button is at the
   foot of the runs page for changing what is shared later.

On Android 13 Health Connect is an app from the Play Store, and the card offers to get it; from
14 it is part of the system. The page Health Connect links to for "why does this app want my
data" is `HealthRationaleActivity`, which Android requires before it will show the sheet at all.

### Readiness

A score out of 100 for how ready the body looks for hard training today, in a ring on the Body
card with the readings behind it. It is this app's own (`ReadinessScore`), a plain one, and
**not Fitbit's "Daily Readiness"**, which is worked out on Google's servers and isn't written to
Health Connect.

| Part | Weight | How it is scored |
|---|---|---|
| Heart rate variability overnight (RMSSD) | 40% | Against this person's own last 28 days, on its logarithm: 75 when it is what is usual, 25 more or fewer for each standard deviation above or below |
| Resting heart rate | 30% | The same, turned over: lower than usual is better |
| Last night's sleep | 30% | Time asleep: 100 at eight hours or more, 0 at four or fewer, even between |

- The score is the weighted average of whichever parts there are. 80 and up reads **Ready to
  push**, 55 to 79 **Steady**, under 55 **Take it easy**.
- Variability and the resting rate are held against one's own baseline and not a population's,
  and variability on its logarithm, after Plews et al. 2013. Neither is scored until there are
  seven days to compare with; until then the score is sleep's alone and the card says it is
  still learning. A standard deviation is never taken as smaller than a floor (about 8% for
  variability, 1.5 beats for the resting rate), so a week of near-identical nights doesn't make
  an alarm of one ordinary night.
- Seven hours is the least recommended for an adult (Watson et al. 2015); eight is the mark
  used here for someone training.
- It needs something from last night: the sleep itself, or the variability measured in it. A
  reading after six in the evening belongs to the night ahead. Before the band has synced in the
  morning the card says **Waiting for last night**, and shows no number sooner than a stale one.
- A night is the longest sleep that ended on that day, so an afternoon nap doesn't stand for the
  night before it; a sleep of under an hour is nobody's night.

It is a guide. Nothing in the [progression rules](#what-to-aim-for) reads it.

### Steps and sleep

- **Steps**: today's count, the last seven days as bars with today's lit, and what a day usually
  comes to (the days before today the band was worn on). The count is Health Connect's own
  total, which counts a step once when both the phone and the band saw it.
- **Sleep**: last night's time asleep, the night as a bar of its stages (deep, REM, light, then
  awake, the deeper the fuller the colour), when it began and ended, and the average of the week
  before it.

A kind that isn't shared says **Not shared** on its tile, which is a different thing from there
being nothing to show.

### Runs

Runs are **not started in this app**. The band records one (or the Google Health app does, with
the phone's GPS), and it turns up behind the **Runs** door on Today once the band has synced:
this week's miles and the last run in a line on the door, and behind it:

- **The runs page**: this week in three numbers (miles, runs, time), the last eight weeks' miles
  as bars (Monday to Sunday, this week's lit), and every run of the last sixteen weeks, the
  latest first: what it is called (by the part of the day it began in: Morning, Lunch,
  Afternoon, Evening, Night), when, how far, its pace and how long, and the heart's average.
  The **longest** and the **fastest pace** (over a mile or more) carry a trophy once there are
  two runs to choose between; a treadmill run says so.
- **A run's page**: distance, pace and time large; the heart's average and maximum, calories,
  cadence, climb and steps under them; then, once its track has been read:
  - **The route**, as a line with its start ringed and its finish dotted. No map is drawn under
    it: the shape is enough to know a route by, and a map would send where the runner lives to a
    tile server. Health Connect hands another app's routes over one at a time, each when asked:
    **Show the route** puts up its sheet, which also offers to allow all of them.
  - **Heart rate** through the run as a line over bands of the [zones](#zones)' colours, and the
    time in each zone. The zones are the ones set on the heart-rate page; with none set the page
    says so and links there. A reading stands until the next one, for up to five minutes.
  - **Splits**, mile by mile: the pace, a bar that is longer the quicker the mile, and the
    heart's average through it, with the part of a mile the run ended on as its own row. A mile
    mark that falls inside a stretch of recorded distance is taken as that far through it.
    Splits are only shown when the distance came in at least three stretches a mile; coarser
    than that, where a mile began would be a guess.

Miles, and feet for a climb, as the weights are pounds. Pace is moving time over distance: what
the run was paused for is left out when the tracker says.

### What it can't do

- **It has never been run against a real Health Connect.** `HealthSource.android.kt` compiles
  against the library and everything above it is tested against a fake, as the heart-rate radio
  was: which records Google Health really writes for a Fitbit Air (whether there are stages,
  variability, distance fine enough for splits, routes) is only known once it has been tried on
  the phone. `adb logcat -s HomeSafeHealth` says what was read, without the readings.
- No live run: no GPS is used by the app and nothing is recorded by it. The live heart rate on a
  workout is the Bluetooth sensor's, above.
- Without the history permission Health Connect only gives the thirty days before the app was
  first let in, and everything since.
- Runs are not in the log: they aren't on History's calendar, aren't in [a copy of the log](#moving-the-log),
  and aren't on another device.

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

A copy is written to the database as one transaction (`FitnessDao.writeMerge`), so one cut off
part-way leaves nothing of itself behind; and every write to the log takes the repository's one
lock, so the copy that is sent is the log at one moment.

The text (`LogCopyText`) is JSON that opens with `"percysafeTrainingLog": 1`, the format's
version. A set is written inside its exercise under one-letter names, about thirty characters
each, because the whole of it has to fit in what one Android app can hand another
(`FitnessShares.MAX_LENGTH`, 200,000 characters): a log of a few hundred sets comes to about a
tenth of that. Enums travel by name and fall back as they do from the database. A log
too long for that isn't offered to the share sheet at all, and the Lifts tab says it couldn't be
sent, since it would otherwise look sent and arrive as nothing. Text
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

## The figure on an exercise

Every exercise is one of twenty-three movements (`MovementKind`; `MovementClassifier` reads which
from its name, shelf and muscle, so a hack squat, a goblet squat and a lunge are all a squat to
look at). A figure performs it: a still of the hard end of the rep on the exercise's row in a
workout, with today's set count on its corner, and the rep itself, over and over, on the open
card and at the top of the exercise's own page, with the muscles that do the lift and the ones
that help it named.

There are three figures to choose between, under **Settings → Exercise figures** or with the
chips under the figure on an exercise's page (the same choice, kept in the same place):

| Figure | What it is |
|---|---|
| **True 3D** (the default) | A modelled lifter rendered ahead of time (`tools/movement-render`): a rep as twenty-five frames, each faded into the next as the rep moves so that it runs smoothly, with the working muscles laid over it in their roles' colours, brighter as the rep nears its hard end |
| **Stone** | The jointed rig (`ui/movement/MovementRig.kt`) drawn live as a figure of pale stone |
| **Forged study** | The same rig as an anatomy study in dark iron, each working head gauged |

The rendered frames are packed per movement under `composeResources/files/movement/<kind>/`:
`body.webp` and `muscle.webp` (five-by-five sheets of the rep, the second holding prime movers,
synergists and stabilizers in its red, green and blue), `badge.webp` (the still) and
`anchors.json` (where each working head is, frame by frame, for the names). A badge is small
and all of them are kept once read; the two sheets are some twenty megabytes decoded, so only a
figure drawn large holds them, one movement's at a time, and lets them go when it leaves the
screen (`MovementPictures`).

The lifter in those frames is Blender Studio's realistic male, a sculpted body from its Human
Base Meshes bundle (CC0, so the renders carry no licence of their own). The model's file is too
large for the repository: `tools/movement-render/sculpted.py` says where to get it, gives it a
skeleton and skins it, and without the file the renderer falls back to a MakeHuman body. The
forty-nine muscle heads are laid on its skin by the body's own landmarks
(`tools/movement-render/muscles.py`), so the same map follows either body.

**Every exercise has a figure.** All twenty-three movements have their frames packed
(`MovementPicturesTest` holds the folder to that); one whose frames can't be read all the same
is drawn as the forged study instead. The rig performs a kind with its movement of the
same name (`Movement`); a kind it has none for is performed by the nearest it has
(`MovementCatalog.standIn`, a figure doing the wrong lift, there only so that a kind added
before the rig has learnt it is never left without a figure).

### A new exercise, and a new movement

An exercise added in the app (by name, or from notes) needs nothing done for it: `MovementClassifier`
reads its movement from its name, and a name that says no more than its shelf gets that shelf's
plainest movement (anything filed under legs that isn't recognised is a squat). So it always has
a figure, though for an exercise unlike any of the twenty-three it is the nearest one, doing
something else. Where that is so the screens say it: when nothing in the name says which movement
it is (`MovementMatch.named` is false, the movement having come from the shelf or the muscle
alone), a line under the large figure reads "Nearest figure: Squat" (`NearestFigureNote`). That
line is the list of exercises still wanting a movement of their own, or a clearer name.

A movement of its own is four things, and the tests name whichever is still missing:

1. **The kind.** An entry in `MovementKind` with its name (`fitness_movement_…` in
   `strings_fitness_movement.xml`), and the words that mean it in `MovementClassifier`
   (`MovementClassifierTest`).
2. **The rig**, for the stone figure and the forged study: an entry of the same name in
   `Movement` (`MovementRig.kt`: the pose at each end of the rep, and what it is done on) and
   which heads work in it (`MovementAnatomy.kt`). `MovementCatalogTest` fails until it is there.
3. **The frames**, for True 3D, which are rendered on a Mac with Blender and the model:
   - in `tools/movement-render/lifts.py`, how the body is held through the rep, written with what
     the others use (`stand`, `sit`, `hold_bar`, `hang`, `rest_hands`; `until_clear` keeps what is
     held out of the body), and its line in `LIFTS` with its equipment and camera;
   - in `muscles.py`, which heads work in it (`WORK`); and its name in `all.sh`;
   - `clip.py -- <lift>` to see that nothing is sunk into the body, `frames.sh` to look at a few
     frames, then `all.sh <directory>` to render and `pack.py` to bring the frames into
     `composeResources/files/movement/<kind>/`. `MovementPicturesTest` fails until they are there.
4. Nothing to write for the names: the heads are already named, unless the movement works one
   that none before it did.

## How it's built

- `fitness/domain`: the models, `Strength`, `Progression`, `Volume`, `NotesParser`, `NotesImport`,
  `ExerciseClassifier`, `LogCopy.kt` (a copy of the log, as text and merged into another); and for
  the heart rate `HeartRate.kt` (zones, the measurement's bytes,
  settling into a zone, time in zone) and `HeartRateSensor.kt` (the sensor's states and the two
  interfaces below). Pure Kotlin.
- `fitness/data`: `FitnessRepositoryImpl` over `FitnessDao` (eight tables in the app's Room
  database: five from schema 19, the heart-rate settings and each workout's time in zone from
  schema 20, and the app's preferences from schema 22; `data/FitnessEntities.kt`). Everything
  stays on the device. The preferences (`FitnessPreferencesRepository`: which figure is drawn)
  are this install's own, and no part of the log or of a copy of it.
- The heart-rate sensor is in two halves. `HeartRateLink` is the platform's radio and carries
  only bytes (`createHeartRateLink`; Android scans for the Heart Rate Service and holds a GATT
  link, the others are stubs). `HeartRateMonitorImpl` is everything else, in common code: which
  advertisement is the chosen sensor, reading the measurement, and trying again when a link
  drops, goes quiet or never comes up. So all of that is tested against a fake radio.
- The health store is in two halves the same way. `HealthSource` is the platform's and only
  hands records over (`createHealthSource`; Android reads Health Connect, the others are stubs).
  What they mean is common code: `Body.kt` (`SleepNights`, `ReadinessScore`, `BodyBoards`) and
  `Runs.kt` (a run's pace, the weeks, splits, the heart in zones). `HealthViewModel` sits beside
  `FitnessViewModel`, scoped to the activity, and reads when the fitness app comes on screen.
- `fitness/FitnessBoard.kt`: `FitnessBoardBuilder` works every screen's state out of the whole
  log in one pass, the history's sessions included. The log is a few thousand rows at most, so it is simply rebuilt when it changes.
- `fitness/FitnessViewModel.kt`: scoped to the activity and shared with the drawer's card. It
  follows the log once the drawer or the app has been on screen, and holds the open workout's
  rest timer, the record flash and the import draft. The heart rate is a second state beside
  the first (`heart`), since it alone changes every second.
- `fitness/ui`: the screens. `FitnessAppContent` is the whole app from one state, for previews
  (`./gradlew :shared:renderPreviews -Ppreview=Fitness`) and UI tests. `BodyUi.kt` is Today's
  body card, `RunsUi.kt` the runs; `HealthFixtures` is a made-up health store to draw them from.
- `fitness/ui/movement`: the figures. `ExerciseFigure` is the one the screens use, a
  `MovementKind` drawn in a `FigureStyle` as a badge or large; behind it, the rendered frames
  (`MovementSheets.kt`), the rig and its two looks, the map from one to the other
  (`MovementCatalog`), and the heads' names (`HeadLabels.kt`).

## Tests

- `commonTest/.../fitness/domain`: the parser against notes with every quirk above, the import
  plan, records, the progression table, volume, the bodyweight trend, and `LogCopyTest` (a copy
  as text and back, and what it adds to an empty log, the same log and one in use).
- `commonTest/.../fitness`: `FitnessBoardBuilderTest`, and `FitnessViewModelTest` over the real
  repository and an in-memory DAO; `ui/FitnessFormatTest` for how a month, a day, a time and a
  workout's length are written. `jvmTest/.../FitnessLogCopyDatabaseTest` deletes a workout
  through a real SQLite file, and checks that one whose deletion fails part-way is left whole.
- Heart rate: `HeartRateTest` (zones, the measurement's bytes, settling, time in zone),
  `HeartRateMonitorImplTest` (the connection's whole life against a fake radio, on virtual time),
  `FitnessHeartViewModelTest`, and `jvmTest/.../FitnessHeartMigrationTest`, which takes a real
  SQLite file from schema 19 to 20 and checks the training log is still in it. The Android radio
  itself (`HeartRateLink.android.kt`) has no test: it needs a band.
- The body and runs: `domain/BodyTest` (nights out of sleep sessions, each part of the readiness
  score and the score, Today's board) and `domain/RunsTest` (pace, the weeks, splits, the heart
  in zones), `HealthViewModelTest` over a fake store (what is asked of the user, what is read
  and when, a read that fails, a run opened), and `uiTest/.../FitnessHealthUiTest`. The Android
  reader itself (`HealthSource.android.kt`) has no test: it needs a phone with Health Connect.
- The figures: `MovementClassifierTest` (every exercise is a movement), `MovementCatalogTest`
  (every movement has a rig figure; the anchors' reader), `FitnessPreferencesRepositoryImplTest`,
  `jvmTest/.../FitnessFigureMigrationTest` (schema 21 to 22 on a real SQLite file), and
  `jvmTest/.../MovementPicturesTest`, which reads every packed movement's files the way the app
  does, so that the renderer and the app can't drift apart unnoticed.
- `uiTest/.../FitnessAppUiTest`: the screens through their test tags.
- `jvmTest/.../FitnessShadersCompileTest` and `androidDeviceTest/.../FitnessShadersTest`: every
  shader compiles, on Skia and on a device's AGSL compiler.
