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
    case weather, atlas, packages, fitness, finance, charging

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
        } else if app == IosAppTab.packages {
            self = .packages
        } else if app == IosAppTab.fitness {
            self = .fitness
        } else if app == IosAppTab.charging {
            self = .charging
        } else {
            self = .finance
        }
    }

    /// Whether this is one of the apps, which only an iPad's sidebar lists.
    var isApp: Bool {
        switch self {
        case .home, .moments, .settings: false
        case .weather, .atlas, .packages, .fitness, .finance, .charging: true
        }
    }
}

@available(iOS 26.0, *)
@MainActor
@Observable
final class ShellCoordinator {
    var selection: ShellSelection = .home
    /// Whether the tab bar is out of the way: the shared module says so for the screens that
    /// want the whole display (the clip editor, car tagging).
    var tabBarHidden = false
    let shell = IosShell()
    /// The hold on the bar that opens the assistant; set at the end of `init`, once `self` exists.
    @ObservationIgnored private(set) var assistantHold: AssistantBarHold? = nil

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
        assistantHold = AssistantBarHold { [weak self] frame in
            self?.toggleAssistant(from: frame)
        }
    }

    /// The bar was held: the assistant of the tab that is up opens out of it (or closes).
    private func toggleAssistant(from frame: CGRect) {
        let (x, y, width, height) = (Double(frame.minX), Double(frame.minY), Double(frame.width), Double(frame.height))
        switch selection {
        case .home: shell.toggleAssistant(tab: IosTab.home, x: x, y: y, width: width, height: height)
        case .moments: shell.toggleAssistant(tab: IosTab.moments, x: x, y: y, width: width, height: height)
        case .settings: shell.toggleAssistant(tab: IosTab.settings, x: x, y: y, width: width, height: height)
        case .weather: shell.toggleAppAssistant(app: IosAppTab.weather, x: x, y: y, width: width, height: height)
        case .atlas: shell.toggleAppAssistant(app: IosAppTab.atlas, x: x, y: y, width: width, height: height)
        case .packages: shell.toggleAppAssistant(app: IosAppTab.packages, x: x, y: y, width: width, height: height)
        case .fitness: shell.toggleAppAssistant(app: IosAppTab.fitness, x: x, y: y, width: width, height: height)
        case .finance: shell.toggleAppAssistant(app: IosAppTab.finance, x: x, y: y, width: width, height: height)
        case .charging: shell.toggleAppAssistant(app: IosAppTab.charging, x: x, y: y, width: width, height: height)
        }
    }
}

/// Holding the glass bar opens the assistant, as holding the Compose bar does everywhere else
/// (`assistantTrigger` in the shared module). SwiftUI has no way to watch a press on the bar
/// itself, so this reaches the `UITabBar` under the `TabView` through a tab's view controller
/// and adds a long press to it, once. Once the hold is recognised the touch is the bar's: the
/// tab under the finger isn't selected when it lifts. A press that moves first (the bar's own
/// drag between tabs) never becomes a hold.
@available(iOS 26.0, *)
@MainActor
final class AssistantBarHold: NSObject, UIGestureRecognizerDelegate {
    private weak var tabBar: UITabBar?
    private let onHold: (CGRect) -> Void

    init(onHold: @escaping (CGRect) -> Void) {
        self.onHold = onHold
    }

    /// Finds the bar above `controller` and watches it; does nothing once it is watched, or while
    /// the controller isn't under the tab bar controller yet.
    func attach(from controller: UIViewController) {
        guard tabBar == nil, let bar = controller.tabBarController?.tabBar else { return }
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(held(_:)))
        hold.delegate = self
        bar.addGestureRecognizer(hold)
        tabBar = bar
    }

    @objc private func held(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began, let bar = tabBar else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        // In the window's points, which is where each tab's Compose view lays out too.
        onHold(bar.convert(bar.bounds, to: nil))
    }

    /// Alongside the bar's own recognisers, so the system's press feedback on a tab doesn't keep
    /// the hold from being seen.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
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
                ComposeTabView(controller: coordinator.shell.viewController(tab: IosTab.home), onPlaced: watchBar)
                    .ignoresSafeArea()
                    .toolbar(coordinator.tabBarHidden ? .hidden : .visible, for: .tabBar)
            }
            Tab("Moments", systemImage: "film.stack.fill", value: ShellSelection.moments) {
                ComposeTabView(controller: coordinator.shell.viewController(tab: IosTab.moments), onPlaced: watchBar)
                    .ignoresSafeArea()
            }
            Tab("Settings", systemImage: "gearshape.fill", value: ShellSelection.settings) {
                ComposeTabView(controller: coordinator.shell.viewController(tab: IosTab.settings), onPlaced: watchBar)
                    .ignoresSafeArea()
            }
            TabSection("Apps") {
                appTab("Weather", systemImage: "cloud.sun.fill", value: .weather, app: IosAppTab.weather)
                appTab("Atlas", systemImage: "map.fill", value: .atlas, app: IosAppTab.atlas)
                appTab("Fitness", systemImage: "figure.strengthtraining.traditional", value: .fitness, app: IosAppTab.fitness)
                appTab("Finance", systemImage: "chart.line.uptrend.xyaxis", value: .finance, app: IosAppTab.finance)
                appTab("Packages", systemImage: "shippingbox.fill", value: .packages, app: IosAppTab.packages)
                appTab("Charging", systemImage: "bolt.car.fill", value: .charging, app: IosAppTab.charging)
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

    /// Has the assistant watch the bar above a tab's controller for a hold (see `AssistantBarHold`).
    private func watchBar(_ controller: UIViewController) {
        coordinator.assistantHold?.attach(from: controller)
    }

    private func appTab(_ title: LocalizedStringKey, systemImage: String, value: ShellSelection, app: IosAppTab) -> some TabContent<ShellSelection> {
        Tab(title, systemImage: systemImage, value: value) {
            ComposeTabView(controller: coordinator.shell.appViewController(app: app), onPlaced: watchBar)
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
///
/// `onPlaced` hears the controller once it is in place under the tab bar controller (the next
/// turn of the main queue after SwiftUI hands it over), which is when the bar can be reached.
@available(iOS 26.0, *)
struct ComposeTabView: UIViewControllerRepresentable {
    let controller: UIViewController
    var onPlaced: ((UIViewController) -> Void)? = nil

    func makeUIViewController(context: Context) -> UIViewController {
        placed(controller)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        placed(uiViewController)
    }

    private func placed(_ controller: UIViewController) {
        guard let onPlaced else { return }
        DispatchQueue.main.async { onPlaced(controller) }
    }
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
