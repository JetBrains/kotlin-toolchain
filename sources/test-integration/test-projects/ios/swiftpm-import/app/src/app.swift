#if canImport(PackageDependency)
import PackageDependency
#endif
import KotlinModules

@main
struct iOSApp {
    static func main() {
        #if canImport(PackageDependency)
        ObjCCompatibleSwift().doSomething()
        #endif
        CallSwift().callSwiftThroughTransitive()
    }
}