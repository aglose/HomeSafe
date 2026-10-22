import UIKit
import SwiftUI
import Shared

/// The whole app as one Compose view controller, bottom nav included. What every iOS before 26
/// runs, and the reference the Liquid Glass shell below is measured against.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        #if DEBUG
        return MainViewControllerKt.MainViewController(debugAutofillCredentials: DebugTestCredentials.load())
        #else
        return MainViewControllerKt.MainViewController(debugAutofillCredentials: nil)
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        if #available(iOS 26.0, *) {
            LiquidGlassShell()
        } else {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}

// MARK: - iOS 26: a native Liquid Glass tab bar over Compose tabs

/// The shell on iOS 26: SwiftUI's `TabView` draws the tab bar — floating, glass, blurring the
/// tab's content as it scrolls underneath — and each tab hosts the Compose view controller the
/// shared module makes for it (`IosShell.kt`). Nested navigation, the shared-element video and
/// the live players all stay inside Compose; only the bar moved. Sign-in comes first, as its
/// own Compose screen, and dissolves into the tabs once the server has accepted the login.
///
/// The approach is JetBrains' own for Compose Multiplatform apps:
/// https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html
@available(iOS 26.0, *)
struct LiquidGlassShell: View {
    /// Made when sign-in reports success; nil until then.
    @State private var coordinator: ShellCoordinator? = nil
    /// The sign-in screen stays underneath until the tabs have faded in over it, so the swap is
    /// a dissolve that only ever changes the content — the same hand-over the Compose root
    /// does (see `RootCrossfade` in the shared module).
    @State private var signInDismissed = false

    var body: some View {
        ZStack {
            if !signInDismissed {
                SignInComposeView(onConnected: connected)
                    .ignoresSafeArea()
            }
            if let coordinator {
                ShellTabView(coordinator: coordinator)
                    .transition(.opacity)
            }
        }
    }

    private func connected() {
        let beat: TimeInterval = 0.32
        withAnimation(.linear(duration: beat)) {
            coordinator = ShellCoordinator()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + beat) {
            signInDismissed = true
        }
    }
}

/// Which tab is up. SwiftUI owns it (the bar is native); the shared module asks for a change
/// through `IosShell.onSelectTab` when Compose navigates across tabs — a detection on the
/// Moments tab opening its camera on Home — and through `IosShell.onSelectApp` when a
/// notification or a share opens one of the apps that an iPad's sidebar lists.
///
/// Outside the iOS 26 shell because the menu bar's commands (`ShellCommands`) name it too.
enum ShellSelection: Hashable {
    case home, moments, settings
    case weather, atlas, fitness, finance

    init(_ tab: IosTab) {
        if tab == IosTab.moments {
            self = .moments
        } else if tab == IosTab.settings {
            self = .settings
        } else {
            self = .home
        }
    }

    init(_ app: IosAppTab) {
        if app == IosAppTab.weather {
            self = .weather
        } else if app == IosAppTab.atlas {
            self = .atlas
        } else if app == IosAppTab.fitness {
            self = .fitness
        } else {
            self = .finance
        }
    }

    /// Whether this is one of the apps, which only an iPad's sidebar lists.
    var isApp: Bool {
        switch self {
        case .home, .moments, .settings: false
        case .weather, .atlas, .fitness, .finance: true
        }
    }
}

@available(iOS 26.0, *)
@Observable
final class ShellCoordinator {
    var selection: ShellSelection = .home
    /// Whether the tab bar is out of the way: the shared module says so for the screens that
    /// want the whole display (the clip editor, car tagging).
    var tabBarHidden = false
    let shell = IosShell()

    init() {
        shell.onSelectTab = { [weak self] tab in
            self?.selection = ShellSelection(tab)
        }
        shell.onSelectApp = { [weak self] app in
            self?.selection = ShellSelection(app)
        }
        shell.onTabBarVisibilityChange = { [weak self] visible in
            self?.tabBarHidden = !visible.boolValue
        }
    }
}

/// The tabs, and on an iPad a sidebar: `.sidebarAdaptable` puts the bar at the top of a
/// regular-width window, where it can open out into a sidebar, and at the foot of a compact one
/// as on the iPhone. The sidebar also lists the apps that live beside the cameras — weather, the
/// atlas, fitness and finance — each a tab of its own instead of a card in the drawer; the
/// tab bar keeps to the three it has always had (Apple's advice is five at most there), so the
/// apps are sidebar-only. Where there is no sidebar to list them in — an iPhone, or an iPad
/// window narrowed to a phone's width — they are hidden, and the shell's drawer brings them back
/// (`IosShell.appsInSidebar`).
@available(iOS 26.0, *)
struct ShellTabView: View {
    @Bindable var coordinator: ShellCoordinator
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// An iPad (or a Mac running the iPad app) with room for a sidebar.
    private var appsInSidebar: Bool {
        UIDevice.current.userInterfaceIdiom != .phone && sizeClass == .regular
    }

    var body: some View {
        TabView(selection: $coordinator.selection) {
            Tab("Home", systemImage: "house.fill", value: ShellSelection.home) {
                ComposeTabView(controller: coordinator.shell.viewController(tab: IosTab.home))
                    .ignoresSafeArea()
                    .toolbar(coordinator.tabBarHidden ? .hidden : .visible, for: .tabBar)
            }
            Tab("Moments", systemImage: "film.stack.fill", value: ShellSelection.moments) {
                ComposeTabView(controller: coordinator.shell.viewController(tab: IosTab.moments))
                    .ignoresSafeArea()
            }
            Tab("Settings", systemImage: "gearshape.fill", value: ShellSelection.settings) {
                ComposeTabView(controller: coordinator.shell.viewController(tab: IosTab.settings))
                    .ignoresSafeArea()
            }
            TabSection("Apps") {
                appTab("Weather", systemImage: "cloud.sun.fill", value: .weather, app: IosAppTab.weather)
                appTab("Atlas", systemImage: "map.fill", value: .atlas, app: IosAppTab.atlas)
                appTab("Fitness", systemImage: "figure.strengthtraining.traditional", value: .fitness, app: IosAppTab.fitness)
                appTab("Finance", systemImage: "chart.line.uptrend.xyaxis", value: .finance, app: IosAppTab.finance)
            }
            .hidden(!appsInSidebar)
        }
        .tabViewStyle(.sidebarAdaptable)
        // The app's Material primary (FrigateDarkColorScheme.primary, #FFB59D) for the selected
        // tab, and the dark glass: the shared theme is dark-only, so the bar must not follow the
        // system into light.
        .tint(Color(red: 1.0, green: 0.71, blue: 0.616))
        .preferredColorScheme(.dark)
        .focusedSceneValue(\.shellSelection, $coordinator.selection)
        .focusedSceneValue(\.shellAppsInSidebar, appsInSidebar)
        .onChange(of: appsInSidebar, initial: true) { _, inSidebar in
            coordinator.shell.appsInSidebar = inSidebar
            // Narrowed to a phone's width while an app was up: it has no tab there, so Home is,
            // and the app is a card in the drawer again.
            if !inSidebar && coordinator.selection.isApp {
                coordinator.selection = .home
            }
        }
    }

    private func appTab(_ title: LocalizedStringKey, systemImage: String, value: ShellSelection, app: IosAppTab) -> some TabContent<ShellSelection> {
        Tab(title, systemImage: systemImage, value: value) {
            ComposeTabView(controller: coordinator.shell.appViewController(app: app))
                .ignoresSafeArea()
        }
        .tabPlacement(.sidebarOnly)
    }
}

/// One tab's Compose view controller, edge to edge. The controller is made once by the shared
/// module and handed back on every tab switch, so nothing is rebuilt. Ignoring the safe area
/// is what lets the content scroll under the glass bar; UIKit still reports the bar (and the
/// status bar) to Compose as the view's safe-area insets, which is what the shared module's
/// `bottomNavClearance` keys off under a native tab bar.
@available(iOS 26.0, *)
struct ComposeTabView: UIViewControllerRepresentable {
    let controller: UIViewController

    func makeUIViewController(context: Context) -> UIViewController { controller }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// The sign-in screen for the iOS 26 shell. `onConnected` fires on the main thread once the
/// server has accepted the login (and any offer to save it for Face ID has been answered).
@available(iOS 26.0, *)
struct SignInComposeView: UIViewControllerRepresentable {
    let onConnected: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
        return IosShellKt.SignInViewController(onConnected: onConnected, debugAutofillCredentials: DebugTestCredentials.load())
        #else
        return IosShellKt.SignInViewController(onConnected: onConnected, debugAutofillCredentials: nil)
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
