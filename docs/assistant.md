# The assistant

Claude, over every screen. Hold any bottom bar (the shell's pill or its side rail, or the Finance,
Weather, Fitness or Atlas app's own bar) and a small box grows out of it, edged with flowing
light, with Claude's answers on a card above it. Nothing new is drawn on any screen to open it:
the long press is the whole of the way in, and a second hold closes it, as does Back (predictive:
the box folds back toward its bar under the finger).

The box floats just above the bar it came from and covers nothing else. The screen beneath isn't
dimmed or blocked, so it keeps scrolling and its tabs keep switching while the box is up, and a
question is asked about whatever is showing by then.

## What Claude knows

- **The screen.** Every question goes with a `<screen>` block: the local time and a line from
  each part of the app that is showing (the shell's tab and the page drilled into, the drawer app
  and its tab; see `AssistantScreenContext`). Unless it is turned off in the model menu ("Show
  Claude this screen"), a JPEG of the app as it is drawn goes too, so "what's this?" works on any
  page. The assistant itself is never in the picture. Video drawn by a platform surface may come
  out blank in it.
- **The app's data.** Tools Claude calls when a question needs them, each contributed by the
  module that owns the data (`@ContributesIntoSet(AppScope::class)` of `AssistantTool`):

  | Tool | Module | What it reads |
  | --- | --- | --- |
  | `list_cameras` | shared | the cameras, enabled, indoor/outdoor, Frigate or Nest |
  | `recent_moments` | shared | detections, newest first, optionally for one camera |
  | `doors` | shared | each door open or shut, battery, recent openings |
  | `who_is_home` | shared | Away mode's phones, home or away |
  | `server_status` | shared | Frigate's version, load, storage, detector, pipelines |
  | `finance_sheet`, `budget` | finance | the sheet and the month's budget; held back while the finance app is locked |
  | `market_quotes` | finance | prices for symbols, or the watchlist |
  | `weather` | weather | the saved places' forecasts, or a place by name |
  | `training_log` | fitness | workouts and sets, body weight, phase |
  | `location_history` | atlas | one day's visits, or the Atlas overview |

  Tools only read. A tool's output is written for Claude (English, often `toString()` of the
  domain model) and isn't copy; what the screen says while it runs (`activity`) is.

## Models

The menu on the box's chip offers Haiku, Sonnet, Opus and Fable: families, not versions. Which
model each means is the newest of that family that Anthropic's Models API lists for the key
(`latestModels`), looked up once a day and whenever a key is saved, so a new release appears
without an app update. Until the list has been read the app uses the ids it shipped with
(`AssistantModelFamily.fallbackId`). Opus is the default.

Requests stream, use adaptive thinking at `medium` effort, cache the conversation prefix, and
ask for Anthropic's server-side refusal fallback (`fallbacks: "default"`) on the families that
have one. The conversation history is only ever appended to: a turn's content blocks go back
exactly as they came, thinking signatures included. A declined question is dropped from the
history rather than edited out.

## The key

The assistant uses the person's own Anthropic API key, pasted into the box the first time it is
opened. It never leaves the phone except to Anthropic. On Android it is sealed with an AES key held
in the Android Keystore; on iOS it is in the Keychain (this device only). "Forget the API key" in
the model menu removes it. Calls go through their own HTTP client, so Frigate's session cookies
never ride along.

## The look

The glow is a runtime shader (`ASSISTANT_GLOW_SHADER`): AGSL on Android, the same source as a Skia
runtime effect on iOS, with a plain gradient rim where neither runs (previews). The four colours
flow round the box's edge, a halo with outward ripples spills over the screen beneath, and the
light bursts as the box blooms out of the bar, settles to a breathing rim, and breathes faster
while Claude works.

## Not yet

- The iOS 26 host's main tabs sit under the native `TabView` bar, which the Compose trigger can't
  reach, so there the assistant opens from the drawer apps' bars only.
- The conversation lasts as long as the app process; it isn't saved.
