import SwiftUI

/// The iPad's menu bar (and the list ⌘ held down shows): a Go menu with every destination and a
/// shortcut for each, as Apple asks of an app on iPadOS 26 — ⌘1 to ⌘3 for the tabs, ⌘4 to ⌘7 for
/// the apps the sidebar lists. A destination that isn't on offer (before sign-in, or an app with
/// no sidebar to hold it) is disabled rather than hidden, so the menu keeps its shape.
///
/// The selection reaches the menu as a focused value of the window's scene: the shell publishes
/// it (`ShellTabView`), and the menu reads it back here.
struct ShellCommands: Commands {
    @FocusedBinding(\.shellSelection) private var selection
    @FocusedValue(\.shellAppsInSidebar) private var appsInSidebar

    var body: some Commands {
        // Beside View, where the system's sidebar toggle is.
        CommandMenu("Go") {
            destination("Home", .home, key: "1")
            destination("Moments", .moments, key: "2")
            destination("Settings", .settings, key: "3")
            Divider()
            destination("Weather", .weather, key: "4")
            destination("Atlas", .atlas, key: "5")
            destination("Fitness", .fitness, key: "6")
            destination("Finance", .finance, key: "7")
            destination("Charging", .charging, key: "8")
        }
    }

    private func destination(_ title: LocalizedStringKey, _ value: ShellSelection, key: KeyEquivalent) -> some View {
        Button(title) { selection = value }
            .keyboardShortcut(key, modifiers: .command)
            .disabled(selection == nil || (value.isApp && appsInSidebar != true))
    }
}

extension FocusedValues {
    /// The window's shell selection, for the menu bar (`ShellCommands`); nil before sign-in.
    @Entry var shellSelection: Binding<ShellSelection>?

    /// Whether the window's sidebar lists the apps, so the menu can offer them.
    @Entry var shellAppsInSidebar: Bool?
}
