#if DEBUG
import Foundation
import Shared

/// Reads the same gitignored `local.credentials.properties` file the Android debug build uses
/// (see androidApp/build.gradle.kts) so there's one place to edit test credentials for the
/// whole KMP project. The Simulator runs as a normal macOS process, so it can read the repo
/// on disk directly — no Xcode build-setting or Info.plist plumbing needed. This file itself
/// never contains a secret: it only knows how to find and parse the gitignored one, and only
/// compiles into DEBUG builds, so it's absent entirely from release/TestFlight archives.
enum DebugTestCredentials {
    static func load() -> DebugAutofillCredentials? {
        // #filePath is this source file's absolute path on the machine that compiled it —
        // walk up from iosApp/iosApp/ to the repo root. Only meaningful for a local build on
        // the same Mac (like local.properties' sdk.dir), which is exactly the intended use.
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // iosApp/iosApp
            .deletingLastPathComponent() // iosApp
            .deletingLastPathComponent() // repo root
        let propertiesURL = repoRoot.appendingPathComponent("local.credentials.properties")

        guard let contents = try? String(contentsOf: propertiesURL, encoding: .utf8) else {
            return nil
        }

        var values: [String: String] = [:]
        for line in contents.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#"),
                  let equals = trimmed.firstIndex(of: "=") else { continue }
            let key = String(trimmed[..<equals]).trimmingCharacters(in: .whitespaces)
            let value = String(trimmed[trimmed.index(after: equals)...]).trimmingCharacters(in: .whitespaces)
            values[key] = value
        }

        let username = values["test.username"] ?? ""
        guard !username.isEmpty else { return nil }

        return DebugAutofillCredentials(
            serverUrl: values["test.serverUrl"] ?? "",
            username: username,
            password: values["test.password"] ?? ""
        )
    }
}
#endif
