import UIKit
import Shared

/// Asks iOS for this install's APNs device token at every launch and hands it to the shared code,
/// which registers it with the relay; the relay pushes to it straight through Apple's push service
/// (see `onApnsToken` in IosApp.kt, and "APNs" in relay/relay.py). The banners themselves need the
/// notification permission the app already asks for; the token comes either way.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        IosAppKt.onApnsToken(token: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // A simulator, or a build signed without the Push Notifications capability: the app keeps
        // its own in-app alerts, as before.
        print("HomeSafe: no APNs token: \(error.localizedDescription)")
    }
}
