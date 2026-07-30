import ObjCCompatibleSwiftTarget
import KotlinModules
import Foundation

@main
struct iOSApp {
    static func main() {
        ObjCCompatibleSwift().doSomething()
        CallSwift().callSwiftThroughTransitive()

        // Is seems that app relaunches if it exits too fast, workaround with a sleep
        Thread.sleep(forTimeInterval: 1)
    }
}