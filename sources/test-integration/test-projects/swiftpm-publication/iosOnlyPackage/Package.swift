// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "iosOnlyPackage",
    products: [
        .library(
            name: "IosOnlyProduct",
            targets: ["IosOnlyTarget"]
        ),
    ],
    targets: [
        .target(
            name: "IosOnlyTarget"
        ),
    ]
)
