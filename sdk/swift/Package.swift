// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MeshNet",
    platforms: [
        .iOS(.v15),
        .macOS(.v13),
        .watchOS(.v8)
    ],
    products: [
        .library(name: "MeshNet", targets: ["MeshNet"])
    ],
    targets: [
        .target(name: "MeshNet"),
        .testTarget(name: "MeshNetTests", dependencies: ["MeshNet"])
    ]
)
