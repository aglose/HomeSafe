import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // Builds the shared graph before any view exists. A home-geofence crossing can relaunch
        // the app in the background with no UI at all; the location manager must already be
        // there to receive it. See IosApp.kt.
        IosAppKt.startIosApp(webRtc: WebRtcPeerBridgeFactory.shared)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
