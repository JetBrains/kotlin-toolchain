import FirebaseCore
import FirebaseFirestore
import KotlinModules
import SwiftUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Color.clear
        }
        // If the app exits before the system finishes launching it, the launch is considered failed and the app is
        // relaunched, which duplicates its output or makes `simctl launch` fail. So we only run once the app is active.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            callFirebase()
            // exit right away, so that Firestore doesn't start networking and log gRPC shutdown errors to stderr
            fflush(stdout)
            exit(0)
        }
    }

    private func callFirebase() {
        let opts = FirebaseOptions(googleAppID: "1:1234567890:ios:abcdef123456", gcmSenderID: "1234567890")
        opts.apiKey = "AIzaSyDrandomKeyGeneratedForDebug001234"
        opts.projectID = "dummy"
        FirebaseApp.configure(options: opts)

        CallFirebase().createFirestore().collection("users").document("local_user")
            .setData(["name": "John Doe", "isOffline": true])
        print("Returning from Swift")
    }
}
