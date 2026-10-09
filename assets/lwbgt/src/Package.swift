// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "lwbgt",
    products: [
        .library(name: "CLWBGT", targets: ["CLWBGT"]),
    ],
    targets: [
        .target(
            name: "CLWBGT",
            path: ".",
            sources: ["src/wbgt.c", "src/lwbgt_ffi.c"],
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedLibrary("m", .when(platforms: [.linux])),
            ]
        ),
    ],
    cLanguageStandard: .c11
)
