# Releasing to Play internal testing

Every push to `main` that gets a green CI publishes a signed Android App Bundle to the
**internal testing** track. The job is `publish-internal` in
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml); it runs only after `ci-green`, so a
failing test never reaches a tester's phone.

Nothing here is automatic on a fresh repo — the six secrets below have to exist first, and Play
itself needs some one-time setup that its API cannot do for you.

## What the job does

1. Refuses to start if any release secret is missing (a missing keystore would otherwise fall
   back to the debug key, and a missing `google-services.json` would ship an app that cannot
   register for push — both silent).
2. Decodes the upload keystore and the Firebase config onto the runner.
3. `./gradlew :androidApp:bundleRelease -PversionCode=<run number> -PrequireReleaseSigning=true`.
4. Uploads the `.aab` to the internal track with `status: completed`, along with `mapping.txt`
   so Play can deobfuscate R8-minified crash reports.
5. Keeps the bundle and its mapping file as a workflow artifact for 90 days. The mapping is the
   only way to read a stack trace from that build once R8 has renamed everything, so keep it.

The release notes shown to testers are the commit subject plus the short SHA.

## One-time Play Console setup

The Play Developer API can only publish to an app that already exists, and it cannot perform the
very first upload. So, by hand, once:

1. Create the app in [Play Console](https://play.google.com/console) with package name
   `com.meticulouscreations.homesafe`.
2. Upload one bundle manually to the internal testing track and complete the app content
   questionnaires Play blocks releases on (privacy policy, data safety, content rating, target
   audience). Build one locally with:

   ```
   ./gradlew :androidApp:bundleRelease -PversionCode=1
   ```

   It lands in `androidApp/build/outputs/bundle/release/androidApp-release.aab`.
3. Add your testers to the internal testing track and accept the opt-in link yourself.
4. Leave **Play App Signing** enrolled (the default). The key in `androidApp/keystore/` is then
   the *upload* key: Play re-signs with the app signing key it holds. That is what makes the
   upload key replaceable if it is ever lost — but back it up anyway, because replacing it takes
   a support round-trip.

### The service account

The upload needs a Google Cloud service account that Play trusts:

1. Play Console → **Setup → API access** → link (or create) a Google Cloud project.
2. Create a service account there; in Google Cloud, give it a **JSON key** and download it.
3. Back in Play Console → **Users and permissions**, invite the service account's email and grant
   it, for this app: *View app information*, *Create and edit draft releases*, and
   *Release to testing tracks*. It needs nothing else — in particular it does not need production
   release rights.
4. Permission changes take a few minutes to propagate; the first upload attempt right after
   granting them can still 401.

## The secrets

Put all six in the repository's **`play-internal` environment**
(Settings → Environments → New environment → `play-internal` → *Environment secrets*). Keeping
them there rather than at repository level means no other job in this workflow can read them, and
lets you add a required reviewer later if you ever want a human to approve a publish.

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `androidApp/keystore/homesafe-release.jks`, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `ANDROID_KEY_ALIAS` | `keyAlias` from `keystore.properties` |
| `ANDROID_KEY_PASSWORD` | `keyPassword` from `keystore.properties` |
| `PLAY_SERVICE_ACCOUNT_JSON` | the whole service-account JSON key file, verbatim |
| `GOOGLE_SERVICES_JSON` | `androidApp/google-services.json`, base64-encoded |

`GOOGLE_SERVICES_JSON` already exists — the `gradle` matrix jobs use it. Copy it into the
environment as well, or the publish job will not see it.

To produce the two base64 values on this Mac (the `-w0`-less form is the macOS one; both files are
gitignored, so this is the only place they exist):

```bash
base64 -i androidApp/keystore/homesafe-release.jks | pbcopy
```

```bash
base64 -i androidApp/google-services.json | pbcopy
```

## versionCode

`androidApp/build.gradle.kts` reads `-PversionCode`, defaulting to `1`. CI passes
`github.run_number`, which is unique and only ever increases for this workflow — no state to keep,
no bump commits.

Play rejects any upload whose `versionCode` is not strictly greater than every code already
uploaded. If the manual first upload used a code at or above the current run number, set the
repository variable `VERSION_CODE_OFFSET` (Settings → Secrets and variables → Actions →
*Variables*) to a number that lifts the run number past it. Only ever raise it.

`versionName` stays `1.0` for every internal build; the `versionCode` is what distinguishes them
in the Play Console.

## Things worth knowing

- **Back-to-back merges cancel each other.** The workflow's `concurrency` group has
  `cancel-in-progress: true`, so a second push to `main` cancels the first run — including a
  publish that is mid-upload. The newer commit publishes instead, which is usually what you want,
  but it means a versionCode can be skipped.
- **A debug-signed bundle can never be uploaded.** `-PrequireReleaseSigning=true` makes the
  build fail rather than fall back to the debug key. Local builds keep the old lenient behaviour.
- **Testing a change to the publish job.** It only runs on `push` to `main`, so a PR will not
  exercise it. Either accept the first merge as the test, or temporarily add
  `workflow_dispatch:` to the `on:` block and a matching condition to run it by hand.
