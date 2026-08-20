// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "packageDependency",
    products: [
        .library(
            name: "packageProduct",
            targets: ["ObjCCompatibleSwiftTarget"]
        ),
        .library(
            name: "packageProduct2",
            targets: ["AnotherTarget"]
        ),
    ],
    targets: [
        .target(
            name: "ObjCCompatibleSwiftTarget",
            dependencies: [
                "ObjCTarget",
            ]
        ),
        .target(
             name: "ObjCTarget"
        ),
        .target(
            name: "AnotherTarget"
        ),
    ]
)
