import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        #if DEBUG
        return MainViewControllerKt.MainViewController(debugAutofillCredentials: DebugTestCredentials.load())
        #else
        return MainViewControllerKt.MainViewController(debugAutofillCredentials: nil)
        #endif
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}