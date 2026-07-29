// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "packageDependency",
    products: [
        .library(
            name: "packageProduct",
            targets: ["ObjCCompatibleSwiftTarget"]
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
             name: "ObjCTarget",
        )
    ]
)
