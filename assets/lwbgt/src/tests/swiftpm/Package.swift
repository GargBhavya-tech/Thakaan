// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CLWBGTConsumer",
    dependencies: [
        .package(name: "lwbgt", path: "../.."),
    ],
    targets: [
        .testTarget(
            name: "CLWBGTConsumerTests",
            dependencies: [
                .product(name: "CLWBGT", package: "lwbgt"),
            ]
        ),
    ]
)
