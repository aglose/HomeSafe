// The Mac's own notifications for HomeSafe, as a library the app loads into itself
// (MacNotifications.kt, through JNA). Posted from the app's process, they carry its name and icon,
// and each keeps the deep link it opens in its userInfo, which a click hands back: the app opens
// what that one was about however many came after it, and even after HomeSafe has quit and been
// started again. AWT's tray banners, the fallback, can't say which was clicked.
//
//   homesafe_notifications_start(onClick)                  asks to notify (once; the Mac
//                                                           remembers) and reports each click
//   homesafe_notifications_post(title, body, link, sound)  shows one if HomeSafe may, now: 1 if it
//                                                           did, 0 for the caller to show it itself
//
// Built by desktopApp/build.gradle.kts with the Xcode command-line tools.
import Foundation
import UserNotifications

public typealias Clicked = @convention(c) (UnsafePointer<CChar>) -> Void

private let linkKey = "link"

final class Delegate: NSObject, UNUserNotificationCenterDelegate {
    var clicked: Clicked?

    // While HomeSafe is in front too: a camera's alert shouldn't wait for the window to lose focus.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.actionIdentifier == UNNotificationDefaultActionIdentifier, let clicked {
            let link = response.notification.request.content.userInfo[linkKey] as? String ?? ""
            link.withCString { clicked($0) }
        }
        completionHandler()
    }
}

private let delegate = Delegate()

// Only an app bundle has a notification centre: a bare `java` run from Gradle has none.
private var hasCentre: Bool { Bundle.main.bundleIdentifier != nil }

@_cdecl("homesafe_notifications_start")
public func homesafeNotificationsStart(_ clicked: Clicked?) {
    guard hasCentre else { return }
    delegate.clicked = clicked
    let center = UNUserNotificationCenter.current()
    center.delegate = delegate
    center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
}

/// Whether HomeSafe may show a notification now: asked each time, as it can be turned on or off in
/// System Settings while the app runs. Answers within a moment; one that doesn't is a no.
private func allowedNow() -> Bool {
    let answered = DispatchSemaphore(value: 0)
    var allowed = false
    UNUserNotificationCenter.current().getNotificationSettings { settings in
        allowed = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
        answered.signal()
    }
    return answered.wait(timeout: .now() + 2) == .success && allowed
}

@_cdecl("homesafe_notifications_post")
public func homesafeNotificationsPost(_ title: UnsafePointer<CChar>, _ body: UnsafePointer<CChar>, _ link: UnsafePointer<CChar>, _ sound: Int32) -> Int32 {
    guard hasCentre, allowedNow() else { return 0 }
    let content = UNMutableNotificationContent()
    content.title = String(cString: title)
    content.body = String(cString: body)
    let target = String(cString: link)
    if !target.isEmpty { content.userInfo = [linkKey: target] }
    if sound != 0 { content.sound = .default }
    let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
    UNUserNotificationCenter.current().add(request)
    return 1
}
