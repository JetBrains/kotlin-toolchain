// swift-tools-version: 5.9
import PackageDescription
let package = Package(
  name: "transitive",
  platforms: [
    .iOS("15.0")
  ],
  products: [
    .library(
      name: "transitive",
      type: .none,
      targets: ["transitive"]
    )
  ],
  dependencies: [
    .package(
      path: "../../../../transitive/packageDependency"
    )
  ],
  targets: [
    .target(
      name: "transitive",
      dependencies: [
        .product(
          name: "packageProduct",
          package: "packageDependency"
        )
      ]
    )
  ]
)
