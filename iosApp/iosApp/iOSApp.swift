import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Registers for remote notifications and passes the APNs token on (see AppDelegate.swift).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // Builds the shared graph before any view exists. A home-geofence crossing can relaunch
        // the app in the background with no UI at all; the location manager must already be
        // there to receive it. See IosApp.kt.
        // The workout in progress as a Live Activity (WorkoutLiveActivities.swift), and its buttons.
        WorkoutLiveActivities.install()
        IosAppKt.startIosApp(webRtc: WebRtcPeerBridgeFactory.shared, liveActivities: WorkoutLiveActivities.shared)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // A tap on the workout's Live Activity: homesafe://fitness?page=workout.
                .onOpenURL { url in _ = IosAppKt.onOpenUrl(url: url.absoluteString) }
        }
        .commands { ShellCommands() }
    }
}
