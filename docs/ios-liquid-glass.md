# Liquid Glass on iOS

On iOS 26 the bottom navigation is SwiftUI's `TabView`, so it is a Liquid Glass bar: floating,
translucent, blurring the tab's content as it scrolls underneath, with the system's own
selection and animation. Everything else the app shows is still Compose. On iOS 18 and 25, and
on every other platform, the bottom nav is the Compose one in `FrigateAppShell.kt`, unchanged.

The arrangement is the one JetBrains describes for Compose Multiplatform apps in
[Liquid Glass in Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html):
native tabs, Compose content. It differs from that tutorial in stopping at the tab bar. The
tutorial also moves each detail screen into a SwiftUI `NavigationStack`; here nested navigation
stays in Compose, because the camera detail screen's video is a shared element flying from its
grid card, and it binds to the same pooled player the card was showing. Both need the card and
the detail screen in one composition.

## How it fits together

```
ContentView (Swift)
├── iOS < 26:  ComposeView → MainViewController() → App()             // whole app, Compose nav
└── iOS 26:    LiquidGlassShell
    ├── SignInComposeView → SignInViewController(onConnected)         // sign-in, no bottom nav
    └── ShellTabView: TabView                                          // the glass bar
        ├── Tab Home     → IosShell.viewController(.home)     → ShellTab(nav, Home)
        ├── Tab Moments  → IosShell.viewController(.moments)  → ShellTab(nav, Moments)
        └── Tab Settings → IosShell.viewController(.settings) → ShellTab(nav, Settings)
```

- **`ShellNavigation`** (`FrigateAppShell.kt`) holds the shell's navigation state: the
  top-level back stack plus the Home and Settings tabs' nested stacks. The Compose shell
  remembers one; `IosShell` makes one and shares it across all three tab view controllers,
  which is what lets the Moments tab open a detection on the Home stack exactly as before.
  Under the native bar that also means switching to Home, which goes out through
  `IosShell.onSelectTab` to the SwiftUI selection; the bar is the truth about which tab is up,
  and Compose never has to follow a tap on it.
- **`ShellTab`** is one tab of the shell on its own: the top bar, the tab's content and
  nothing at the bottom. `ShellScaffold` skips the Compose bottom nav when
  **`LocalNativeTabBar`** is true, and `bottomNavClearance()` then reserves only a 16dp gap
  above the bottom system inset, which under a `UITabBarController` already includes the bar.
  Every list already pads by that clearance, so content scrolls under the glass and the last
  item still clears it.
- **`AppChrome` / `startAppServices`** (`App.kt`) are what `App()` is made of, pulled out so
  the four iOS 26 view controllers can each have the theme and the view-model factory around
  them while the app-wide services start once.
- **`IosAppVisibility`** drives `AppVisibility` from UIKit's foreground/background
  notifications. `App()` drives it from its root lifecycle, which is right with one root; with
  four, a tab's lifecycle stops whenever another tab is up, and that must not read as the app
  going to the background (the live players would drop every stream on the short window).

## What it costs

- A hidden tab is stopped, not disposed, as it was under Compose navigation. The players
  behave the same (a stopped binder pauses the stream and starts the idle countdown, exactly
  as a disposed one did); `collectAsStateWithLifecycle` in the hidden tab stops collecting.
- Tab switches use the system's transition rather than the shared-axis slide. Compose no
  longer animates between tabs at all on iOS 26.
- `.tabBarMinimizeBehavior(.onScrollDown)` is not used: it follows a native scroll view, and
  the tabs' lists are Compose.

## Building

Liquid Glass needs the iOS 26 SDK, so build with Xcode 26 or later. The deployment target
stays at 18.2; `#available(iOS 26.0, *)` picks the shell at run time. Any change to the
Kotlin/Swift surface (`IosShell.kt`) needs a real device or simulator run to prove it, since
Kotlin/Native's Apple targets only build on macOS.
