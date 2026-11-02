// The Mac app's browser, for the resort ticket shops that answer nothing else (SkiBrowser.kt says
// why). The JVM has no web engine of its own, and this is a few kilobytes against the hundreds of
// megabytes of bundling one: the Mac's own WebKit, as the iPhone's WKWebView uses. Built by
// :desktopApp on a Mac into the app's resources as "HomeSafeBrowser", and run by
// SkiBrowser.desktop.kt, one page per run, so nothing of it stays around and a page that takes it
// down takes nothing else with it:
//
//   HomeSafeBrowser <url> <timeout in milliseconds>      with the script on stdin
//
// Opens the page, and runs the script every half a second until it gives a string, which goes to
// stdout. Exit 0 with an answer, 3 if none came in time, 64 if it was asked wrongly. It identifies
// itself as what it is: nothing about the engine is changed.
import AppKit
import WebKit

let arguments = CommandLine.arguments
guard arguments.count == 3, let address = URL(string: arguments[1]), let timeoutMillis = Int(arguments[2]) else { exit(64) }
let script = String(decoding: FileHandle.standardInput.readDataToEndOfFile(), as: UTF8.self)
let parent = getppid()

// No Dock icon, no menu, and it never takes the focus from the app.
let app = NSApplication.shared
app.setActivationPolicy(.accessory)

let configuration = WKWebViewConfiguration()
// Its own store, gone with it, keeps a resort's cookies out of everything else.
configuration.websiteDataStore = .nonPersistent()
configuration.mediaTypesRequiringUserActionForPlayback = .all
let view = WKWebView(frame: .zero, configuration: configuration)

// WebKit stops a page's timers while its window can't be seen, and the check a shop's firewall
// runs first never finishes without them. So this one can be seen, as far as the Mac goes: a
// couple of points in the corner of the screen, all but transparent, that the mouse goes through.
// It is above other windows only because one behind them is hidden again. The iPhone's does the same.
let corner = NSScreen.screens.first?.visibleFrame.origin ?? .zero
let window = NSWindow(contentRect: CGRect(origin: corner, size: CGSize(width: 2, height: 2)), styleMask: .borderless, backing: .buffered, defer: false)
window.isReleasedWhenClosed = false
window.ignoresMouseEvents = true
window.hasShadow = false
window.alphaValue = 0.01
window.level = .floating
window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
window.contentView = view
window.orderFrontRegardless()

/** Asks the page, and again half a second after it has said nothing: never two at once. */
func ask() {
    view.evaluateJavaScript(script) { result, _ in
        if let answer = result as? String {
            FileHandle.standardOutput.write(Data(answer.utf8))
            exit(0)
        }
        // The app went without saying so: nobody is left to read an answer.
        if getppid() != parent { exit(3) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: ask)
    }
}

DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeoutMillis)) { exit(3) }
view.load(URLRequest(url: address))
DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: ask)
app.run()
