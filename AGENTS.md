# HomeSafe — notes for coding agents

## Strings and localization

**No user-visible text is written as a Kotlin string literal.** Every word a person can read or
hear (labels, buttons, titles, empty states, errors, snackbars, dialogs, content descriptions,
notification titles/bodies/actions, channel names, generated sentences) lives in a strings XML
file, so the app can be translated by adding a `values-<lang>` folder. (iOS Swift follows Apple's
mechanism instead; see "Where strings go".) This applies to screens and components and also to
everything that produces text for them: view models, use cases, domain-model presenters, the
finance narrator and explainers, network/data code whose error messages reach the UI, and
platform notification code. A new screen or a change to an existing one that adds a literal is
not done.

### Where strings go

- **Shared code (`shared/`)**: Compose Multiplatform resources in
  `shared/src/commonMain/composeResources/values/`. `strings.xml` holds the app-wide words
  (`common_cancel`, `common_retry`, …); each area has its own file beside it
  (`strings_moments.xml`, `strings_settings.xml`, `strings_finance_explainers.xml`, …). Every
  `values*/*.xml` file is merged, so **keys must be unique across all files**: prefix them with
  the area (`moments_empty_title`, `settings_notifications_test_button`). Look for an existing
  key before adding one, but don't reuse a key for a different meaning just because the English
  matches; translators need the context.
- **Android-only app code (`androidApp/`)**: `androidApp/src/main/res/values/strings.xml`, read
  with `context.getString(R.string.…)`.
- **iOS Swift (`iosApp/`)**: SwiftUI's `Text("…")` / `Tab("…", …)` take a `LocalizedStringKey`,
  so those literals are already keys; translations go in a `Localizable.xcstrings` String
  Catalog. Anything that is a plain `String` shown to the user uses `String(localized:)`.
- A translation is a sibling folder with the same keys: `composeResources/values-es/strings_moments.xml`.

### How to use them

- In a composable: `stringResource(Res.string.key)`, `stringResource(Res.string.key, arg1, arg2)`,
  `pluralStringResource(Res.plurals.key, count, count)`. Imports come from
  `homesafe.shared.generated.resources.*` and `org.jetbrains.compose.resources.*`.
- Outside a composable (view model, use case, domain presenter, enum label): don't build a
  `String`. Return a `StringResource` (for a fixed label, e.g. `enum class Topic(val title: StringResource)`)
  or a `UiText` (`com.meticulouscreations.homesafe.text.UiText`) for anything with arguments,
  plurals or choices: `UiText.of(Res.string.key, args…)`, `UiText.plural(Res.plurals.key, n)`,
  `UiText.Joined(parts, separator)`. A composable shows it with `text.resolve()`; non-UI code that
  must produce a `String` (a notification, a share sheet) uses `text.load()`, or plain
  `getString(Res.string.key)` (both suspend).
- Data is not copy: camera names, people's and cars' names, Frigate labels and sub-labels, sheet
  contents, numbers and amounts already formatted are passed as arguments or wrapped with
  `UiText.Verbatim` / `"…".asUiText()`. Never wrap English prose in `Verbatim`.
- Errors the UI shows: throw `LocalizedException(UiText.of(Res.string.…), technical = "HTTP 401")`
  where the failure is understood, and show `throwable.userMessage(Res.string.some_fallback)` in
  the view model. Keep anything callers match on (status codes) in `technical`, never in the
  translated text.

### Writing the XML

These rules are for the Compose resources under `composeResources/`. The Android-only
`res/values/strings.xml` files are read by Android itself and follow Android's rules instead
(there an apostrophe is escaped as `\'`).

- One string per line, no leading/trailing whitespace inside the tag (it is kept verbatim).
- Placeholders are positional only: `%1$s`, `%2$d`. Plain `%s` is not substituted. A literal
  percent sign is written as `%`, **not** `%%` (Compose resources does no `%%` unescaping).
- Do **not** backslash-escape apostrophes or quotes: `Couldn't` is written `Couldn't`, because
  Compose resources only unescapes `\n`, `\t`, `\uXXXX` and `\\`; a `\'` would show its
  backslash. XML still needs `&amp;` for `&` and `&lt;` for `<`.
- Counts use `<plurals>` (`one`/`other`), never `if (n == 1) "clip" else "clips"`.
- Whole sentences, not fragments: `"%1$s came and went %2$d×"` as one string, not
  `name + " came and went " + n + "×"`. Word order differs between languages. Pick between whole
  sentences rather than gluing optional clauses on.

### What may stay a literal

Identifiers and plumbing that no person reads: resource keys, routes, deep links, URLs, JSON
field names, Frigate labels/zone/camera ids, SQL, preference keys, notification channel *ids*,
test tags, log lines, `require`/`check` messages for programmer errors, SwiftUI `Text("…")` /
`Tab("…", …)` literals (they are `LocalizedStringKey`s, looked up in the String Catalog), and the sample *data* in
`@Preview`s and tests (a preview's camera called "Front Door" is data; the screen's "No cameras
yet" is copy and comes from resources). Tests in `commonTest` assert on `UiText`/`StringResource`
values structurally; Compose UI tests may match the English text.

Before finishing any UI change, grep what you touched for `"` and check every hit against the
list above.

## Back navigation

Back is predictive: on Android the screen answers the back swipe while the finger is still
moving, not only once it lets go. Everything goes through the NavigationEvent library's
dispatcher. The manifest opts in with `android:enableOnBackInvokedCallback="true"`, which
Android 13-15 need.

- **Screens on a Navigation 3 back stack**: `NavDisplay` handles Back. Give it a
  `predictivePopTransitionSpec` that uses the swipe edge it is passed (`predictiveSharedAxis(it)`
  in `NavTransitions.kt`), so the page moves the way the finger pulls.
- **Anything else that Back closes or pops** (an overlay, a drawer, an `AnimatedContent` page
  stack): use `rememberPredictiveBack(enabled) { releasedAt -> … }` from `ui/PredictiveBack.kt`,
  not `BackHandler`. Draw the surface from `back.progress` during the swipe, and start the exit
  from `releasedAt` so nothing jumps back to rest first. An `AnimatedContent` page stack gets its
  pop scrubbed by `rememberPredictiveBackTransition(target, previous, back, label).AnimatedContent { … }`.
- A handler that only asks first (unsaved changes, then a dialog) stays still under the finger:
  don't draw a peek at a page Back won't reach.
- Handlers are last-registered-first. A nested stack enables its handler only while it has
  something to pop, so Back falls through to the surface around it.
