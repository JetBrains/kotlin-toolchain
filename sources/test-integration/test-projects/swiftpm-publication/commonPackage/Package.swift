// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "commonPackage",
    products: [
        .library(
            name: "CommonProduct",
            targets: ["CommonTarget"]
        ),
    ],
    targets: [
        .target(
            name: "CommonTarget"
        ),
    ]
)
