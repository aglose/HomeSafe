# The desktop app

HomeSafe runs on a Mac as well: the same Compose UI as the phones, in a window of its own, laid out
for a pointer, a keyboard and a wide screen. `:desktopApp` is the app; every other module has a
`desktop` (JVM) target beside Android and iOS, with its platform pieces in `src/desktopMain`.

## Installing it on a Mac

Every run of the **Desktop app** workflow (`.github/workflows/desktop-app.yml`, on each pull
request and on `main`) builds a `.dmg` on an Apple-silicon Mac and keeps it as the run's
**HomeSafe-macOS** artifact for 30 days.

1. Open the run in GitHub's Actions tab and download **HomeSafe-macOS** (a zip with the `.dmg` in it).
2. Open the `.dmg` and drag **HomeSafe** into **Applications**.
3. The app isn't signed with an Apple Developer ID, so the first time macOS says it can't check it.
   Open it once from Finder with **Control-click → Open → Open**, or allow it under **System
   Settings → Privacy & Security → Open Anyway**. If macOS calls the app *damaged* instead (it does
   for a download it can't check), clear the download's quarantine and open it again:

   ```bash
   xattr -dr com.apple.quarantine /Applications/HomeSafe.app
   ```

### Keeping it up to date

`scripts/mac-app-update.sh` puts main's newest build in place of the one in Applications: it asks
GitHub for the newest **HomeSafe-macOS** artifact of a run on `main`, and installs it if it isn't
the one already there. With `--install` it does so every six hours and at login from then on, as a
LaunchAgent (`~/Library/LaunchAgents/com.meticulouscreations.homesafe.updater.plist`, logging to
`~/Library/Logs/HomeSafe-updater.log`). It needs GitHub's CLI signed in to an account that can
read the repository, and no clone, as `--install` keeps a copy of the script for itself:

```bash
brew install gh && gh auth login
```

```bash
gh api repos/aglose/HomeSafe/contents/scripts/mac-app-update.sh -H 'Accept: application/vnd.github.raw' | bash -s -- --install
```

From a clone, `scripts/mac-app-update.sh` updates once, `--install 2` checks every two hours,
`--force` reinstalls the newest build even if it is in place, and `--uninstall` stops it all.

A HomeSafe that is running (in the menu bar, say) is quit, replaced and opened again behind the
other windows, and a notification says which version arrived. One in front is being used, and is
left for the next check. What the app keeps (the sign-in, the settings, the data in
`~/Library/Application Support/HomeSafe`) stays as it was. A copy fetched by `gh` carries no
quarantine, so macOS doesn't ask about it again. The app's signature is ad hoc, so if macOS asks
whether HomeSafe may use the login it saved in the Keychain after an update, **Always Allow**
answers it for that build.

It is built for Apple silicon (M1 and later). An Intel Mac can build its own with
`./gradlew :desktopApp:packageDmg` (below).

## Building and running it

```bash
./gradlew :desktopApp:run            # opens the app, on any OS
./gradlew :desktopApp:packageDmg     # on a Mac: desktopApp/build/compose/binaries/main/dmg/
```

`packageDmg` packages the JDK it runs on (Java 21), so the app is for that Mac's CPU, with that
CPU's Skia and FFmpeg. On a Mac the build also compiles the Touch ID helper
(`desktopApp/macos/OwnerCheck.swift`) and the notifications library (`desktopApp/macos/Notifications.swift`)
with the Xcode command-line tools. `-PdesktopBuild=<n>` sets
the version's last number (`1.0.<n>`); CI passes its run number.

## How it is laid out

The layout follows the [Android CLI](https://developer.android.com/tools/agents/android-cli)'s
`adaptive` skill (`android skills add adaptive`): what a window is wide enough for decides the
layout, never which device it is on. So the same layouts appear on an Android tablet held sideways,
in an Android desktop window and on a Mac.

- **A sidebar** from Material's *expanded* width (840dp, `showsSidebar()` in `ui/WindowLayout.kt`)
  instead of the floating nav and the drawer: Home, Moments and Settings, then Weather, Atlas,
  Fitness and Finance, each shown beside it as on the iPad. The open app's own tabs are listed
  under its row (`publishAppSections` in `ui/AppSections.kt`), and it draws neither its pill nor
  its close button. The assistant has a row at the foot, as there is no nav to hold. View → Hide
  Sidebar (⌃⌘S), or the button beside the title, gives the page the whole window. A window
  narrowed past 840dp goes back to the phone's shell, carrying an open app back over it.
- **Two panes** where the content area (the window less the sidebar) is 800dp or more: Settings'
  list beside the page a row opens, and a camera's activity beside its video.
- **Columns**: lists and cards keep to a readable width (`READABLE_CONTENT_WIDTH`), and Home's
  cameras fill the width in a grid.
- **Pointers**: icon buttons say what they do in a tooltip (`WithTooltip`), with their shortcut
  where there is one, and what acts on a click shows the hand (`handCursor()`).

## Keyboard

The Mac's menu bar has every shortcut, so they can be found there; Ctrl stands in for ⌘ elsewhere.

| Shortcut | Does |
| --- | --- |
| ⌘1 · ⌘2 · ⌘3 | Home · Moments · Settings |
| ⌘4 · ⌘5 · ⌘6 · ⌘7 | Weather · Atlas · Fitness · Finance |
| ⌘K | The assistant, over what is on screen |
| ⌘[ (or the mouse's back button) | Back |
| Esc | Closes what Back would: a dialog, a sheet, a page |
| ⌘, | Settings |
| ⌃⌘S | Show or hide the sidebar |
| ⌘+ · ⌘− · ⌘0 | Zoom in · out · back to actual size |
| ⌃⌘F | Full screen |
| ⌘W · ⌘M · ⌘Q | Close the window · minimise it · quit |

Menu items and the sidebar send `ShellCommand`s (`navigation/ShellCommands.kt`), which the shell
acts on while it is up. On Android a hardware keyboard sends the same, with Ctrl, from
`MainActivity`, which lists them in the system's Keyboard Shortcuts helper too.

## What a computer has, and what it hasn't

| | On the Mac |
| --- | --- |
| Live video and recordings | FFmpeg (JavaCV) plays go2rtc's HLS and Frigate's recordings, decoded on a thread per camera and drawn with Skia (`DesktopVideoEngine.kt`). No WebRTC engine, so live is a second or two behind the phones'. |
| Sound | Through Java Sound, Opus or AAC, when a camera's sound is turned on. |
| Sign-in | Saved in the login Keychain behind Touch ID (or the Mac's password) by the Swift helper; the same helper unlocks Finance and Atlas while the server is away. Weaker than the phones' (see `BiometricCredentialStore.desktop.kt`). |
| Notifications | The alert poller's and the weather's, while the app runs, in the Mac's Notification Center as HomeSafe's own (`macos/Notifications.swift`, loaded through JNA by `MacNotifications.kt`; macOS asks once to allow them). Each keeps the deep link it opens (`homesafe://…`, as Android's do), so clicking one opens what that one was about, even after HomeSafe has quit and started again. While they aren't allowed (asked again for each one), they come from the menu-bar icon instead, and a click opens the latest. No push reaches a computer. |
| Closing the window | Keeps HomeSafe running in the menu bar, so alerts still arrive; ⌘Q quits. The menu-bar icon's first item opens the window, or minimises it when it is up. |
| Downloads | Into `~/Downloads`, shown in Finder. |
| The network | Watched for changes every few seconds; the tailnet is read off the computer's own addresses, and Tailscale.app can be opened from the app. |
| Data | `~/Library/Application Support/HomeSafe` and the app's preferences. |
| App icon | The one chosen in Settings goes in the Dock while the app runs. |
| Not here | Away mode's location (a computer stays home), Health Connect and run recording, heart-rate straps, push. |

## Tests

`./gradlew :app:desktopTest` runs the whole-app journeys (`app/src/integrationTest`) and the
screen-level UI tests (`app/src/uiTest`) on the JVM, against the fake Frigate, and keeps a picture
of every journey step under `app/build/journeys`. Most journeys walk the phone's shell
(`JourneyWindow.PHONE`); `DesktopLayoutJourneyTest` opens a laptop's window
(`JourneyWindow.DESKTOP`) and visits every destination in the sidebar. The tests keep their data
apart from a real install's (`-Dhomesafe.dataDir`, set in `app/build.gradle.kts`).
