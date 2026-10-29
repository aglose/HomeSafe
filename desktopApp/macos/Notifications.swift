// The Mac's own notifications for HomeSafe, as a library the app loads into itself
// (MacNotifications.kt, through JNA). Posted from the app's process, they carry its name and icon,
// and a click hands back the identifier of the one clicked, so the app opens what that one was
// about however many came after it. AWT's tray banners, the fallback, can't say which was clicked.
//
//   homesafe_notifications_start(onClick, onAnswer)   asks to notify (once; the Mac remembers),
//                                                       answers 1 or 0, and reports each click
//   homesafe_notifications_post(id, title, body, sound) shows one, under its identifier
//
// Built by desktopApp/build.gradle.kts with the Xcode command-line tools.
import Foundation
import UserNotifications

public typealias Clicked = @convention(c) (UnsafePointer<CChar>) -> Void
public typealias Answered = @convention(c) (Int32) -> Void

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
            response.notification.request.identifier.withCString { clicked($0) }
        }
        completionHandler()
    }
}

private let delegate = Delegate()

@_cdecl("homesafe_notifications_start")
public func homesafeNotificationsStart(_ clicked: Clicked?, _ answered: Answered?) {
    // Only an app bundle has a notification centre: a bare `java` run from Gradle has none.
    guard Bundle.main.bundleIdentifier != nil else {
        answered?(0)
        return
    }
    delegate.clicked = clicked
    let center = UNUserNotificationCenter.current()
    center.delegate = delegate
    center.requestAuthorization(options: [.alert, .sound]) { allowed, _ in answered?(allowed ? 1 : 0) }
}

@_cdecl("homesafe_notifications_post")
public func homesafeNotificationsPost(_ id: UnsafePointer<CChar>, _ title: UnsafePointer<CChar>, _ body: UnsafePointer<CChar>, _ sound: Int32) {
    let content = UNMutableNotificationContent()
    content.title = String(cString: title)
    content.body = String(cString: body)
    if sound != 0 { content.sound = .default }
    let request = UNNotificationRequest(identifier: String(cString: id), content: content, trigger: nil)
    UNUserNotificationCenter.current().add(request)
}
