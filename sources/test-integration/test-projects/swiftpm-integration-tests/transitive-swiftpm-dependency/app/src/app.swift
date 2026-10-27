import ObjCCompatibleSwiftTarget
import KotlinModules
import Foundation
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
            ObjCCompatibleSwift().doSomething()
            CallSwift().callSwiftThroughTransitive()

            fflush(stdout)
            exit(0)
        }
    }
}
