// swift-tools-version: 6.0
import PackageDescription
import Foundation

let linkageType: Product.Library.LibraryType = switch ProcessInfo.processInfo.environment["LINKAGE_TYPE"] {
    case "dynamic": .dynamic
    case "static": .static
    default: fatalError("Missing LINKAGE_TYPE")
}

let package = Package(
    name: "packageDependency",
    products: [
        .library(
            name: "packageProduct",
            type: linkageType,
            targets: ["ObjCCompatibleSwiftTarget"]
        ),
    ],
    targets: [
        .target(
            name: "ObjCCompatibleSwiftTarget"
        ),
    ]
)
