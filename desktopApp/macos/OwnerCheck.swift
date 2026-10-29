// The Mac app's Touch ID, for the JVM, which has no way to LocalAuthentication of its own. Built by
// :desktopApp on a Mac into the app's resources as "HomeSafe" (the name macOS shows in the prompt:
// "HomeSafe is trying to …"), and run by MacOwnerCheck.kt, one command per run:
//
//   can                  prints touchid, password or none: what the check will ask for
//   confirm <reason>     Touch ID, or the Mac's password, and nothing behind it
//   save <reason>        confirms, then keeps stdin in the login Keychain
//   read <reason>        confirms, then prints what save kept
//   exists               exit 0 when something is kept, 1 when not, without a prompt
//   delete               forgets it, without a prompt
//
// Exit 0 is success, 2 a cancelled prompt, 1 anything else (the system's words on stderr).
import Foundation
import LocalAuthentication
import Security

let service = "com.meticulouscreations.homesafe.biometric"
let account = "saved_credentials"

func fail(_ message: String, code: Int32 = 1) -> Never {
    FileHandle.standardError.write(message.data(using: .utf8)!)
    exit(code)
}

func confirm(_ reason: String) {
    let context = LAContext()
    var error: NSError?
    guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
        fail(error?.localizedDescription ?? "")
    }
    let done = DispatchSemaphore(value: 0)
    var outcome: (Bool, Error?) = (false, nil)
    context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, error in
        outcome = (success, error)
        done.signal()
    }
    done.wait()
    if outcome.0 { return }
    var cancelled = false
    if let code = (outcome.1 as? LAError)?.code {
        cancelled = code == .userCancel || code == .appCancel || code == .systemCancel
    }
    fail(outcome.1?.localizedDescription ?? "", code: cancelled ? 2 : 1)
}

let query: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: service,
    kSecAttrAccount as String: account,
]

let arguments = Array(CommandLine.arguments.dropFirst())
let reason = arguments.count > 1 ? arguments[1] : "HomeSafe"
switch arguments.first ?? "" {
case "can":
    let context = LAContext()
    var error: NSError?
    if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
        print(context.biometryType == .touchID ? "touchid" : "password")
    } else if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
        print("password")
    } else {
        print("none")
    }
case "confirm":
    confirm(reason)
case "save":
    confirm(reason)
    let secret = FileHandle.standardInput.readDataToEndOfFile()
    SecItemDelete(query as CFDictionary)
    var item = query
    item[kSecValueData as String] = secret
    item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    let status = SecItemAdd(item as CFDictionary, nil)
    if status != errSecSuccess { fail((SecCopyErrorMessageString(status, nil) as String?) ?? "\(status)") }
case "read":
    confirm(reason)
    var read = query
    read[kSecReturnData as String] = true
    read[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(read as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else {
        fail((SecCopyErrorMessageString(status, nil) as String?) ?? "\(status)")
    }
    FileHandle.standardOutput.write(data)
case "exists":
    var attributes = query
    attributes[kSecReturnAttributes as String] = true
    exit(SecItemCopyMatching(attributes as CFDictionary, nil) == errSecSuccess ? 0 : 1)
case "delete":
    SecItemDelete(query as CFDictionary)
default:
    fail("usage: HomeSafe can|confirm|save|read|exists|delete [reason]")
}
