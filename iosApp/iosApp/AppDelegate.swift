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

    /// A push that asks to wake the app. Only the relay's "rain on the way" does (`content-available`,
    /// see "rain soon" in relay/relay.py): iOS has drawn its banner already, and the shared code gives
    /// it the picture the app's own notifications wear. iOS decides whether this runs at all.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let payload = userInfo["weather_soon"] as? String, let key = userInfo["notif_id"] as? String else {
            completionHandler(.noData)
            return
        }
        IosAppKt.onWeatherPush(payload: payload, key: key) { completionHandler(.newData) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // A simulator, or a build signed without the Push Notifications capability: the app keeps
        // its own in-app alerts, as before.
        print("HomeSafe: no APNs token: \(error.localizedDescription)")
    }
}
