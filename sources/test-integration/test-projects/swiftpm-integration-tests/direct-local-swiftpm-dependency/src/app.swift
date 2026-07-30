import KotlinModules
import ObjCCompatibleSwiftTarget
import ObjCTarget
import Foundation

@main
struct iOSApp {
    static func main() {
        CallLocalPackage().call()

        // Also make sure the application can compile and link against the package
        ObjCCompatibleSwift().doSomething()
        ObjCTarget().doSomethingObjC()

        // Is seems that app relaunches if it exits too fast, workaround with a sleep
        Thread.sleep(forTimeInterval: 1)
    }
}