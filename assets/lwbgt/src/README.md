# lwbgt

[![PyPI](https://img.shields.io/pypi/v/lwbgt.svg)](https://pypi.org/project/lwbgt/)
[![CRAN](https://img.shields.io/cran/v/lwbgt.svg)](https://cran.r-project.org/web/packages/lwbgt/index.html)
[![R-universe](https://zyf0717.r-universe.dev/lwbgt/badges/version)](https://zyf0717.r-universe.dev/lwbgt)

`lwbgt` computes outdoor wet bulb globe temperature (WBGT) using the original
Argonne Liljegren C calculation. One optimized C kernel serves the C/FFI,
Python, R, Julia, and SwiftPM interfaces.

## Compatibility and performance

**Version: v1.2.0.** Reference comparisons reproduce WBGT and its component
temperatures bit for bit under matched compiler and floating-point settings.
The v1 calculation remains the default throughout 1.x; future numerical
revisions will use explicit versioned APIs while preserving v1. See the
[compatibility policy](https://github.com/zyf0717/lwbgt/blob/main/docs/COMPATIBILITY.md)
for the scope, documented corrections, and platform limits.

The kernel reuses intermediate results and avoids redundant work, preserving
the original numerical precision. Our
[scalar benchmark](https://github.com/zyf0717/lwbgt/blob/main/benchmarks/README.md)
measured 1.45–1.67× the original throughput across two CPU cores with GCC 13.3.0.
Performance depends on the compiler, hardware, and inputs.

## Input assumptions

When some inputs are unavailable, use explicit, recorded assumptions. The
library does not fill in defaults automatically.

| Field | Suggested assumption |
|---|---|
| `pressure_hpa` | Prefer an estimate from site elevation. `1013.25` hPa is a sea-level screening assumption. |
| `wind_height_m` | Use `10` only when the source specifies wind measured at 10 m; otherwise use instrument metadata. |
| `vertical_temperature_difference_c` | `1` assumes a nighttime inversion. Only negative versus nonnegative matters. |
| `urban` | Use `0` for rural or `1` for urban. If unknown, calculate both and retain the higher WBGT for screening. |
| `averaging_minutes` | Use the source averaging interval; `0` is appropriate only for instantaneous or already centered observations. |

Air temperature, humidity, wind speed, and daytime solar radiation have no
general fallback. See the
[input guide](https://github.com/zyf0717/lwbgt/blob/main/docs/INPUTS.md) for units,
timestamp conventions, and when these assumptions affect the calculation.

## Python

```sh
python -m pip install lwbgt
```

```python
from lwbgt import Input, calculate, calculate_batch, esat

weather = Input(
    year=2024, month=4, day=15, hour=14, minute=30,
    gmt_offset_hours=8, averaging_minutes=60, urban=1,
    latitude_deg_north=1.3521, longitude_deg_east=103.8198,
    solar_w_m2=742.0, pressure_hpa=1008.4,
    air_temperature_c=32.1, relative_humidity_percent=68.0,
    wind_speed_m_s=2.8, wind_height_m=10.0,
    vertical_temperature_difference_c=1,
)

result = calculate(weather)
assert result.status == 0
print(result.wbgt_c)

results = calculate_batch([weather, weather])
print(esat(273.15, phase=0))
```

`Input` and `Result` are immutable typed records. Field names, units, status
codes, and failure behaviour are defined by the
[ABI contract](https://github.com/zyf0717/lwbgt/blob/main/docs/ABI.md). See the
[input assumptions](https://github.com/zyf0717/lwbgt/blob/main/docs/INPUTS.md)
before substituting unavailable observations.

Psychrometric wet-bulb is calculated by default. When only WBGT and its
components are needed, use `calculate(weather, psychrometric=False)` or
`calculate_batch(records, psychrometric=False)` to skip the independent solve.
That result field is `-9999`; the remaining fields and status are unchanged.
The C equivalent is `lwbgt_calc_batch_ex_v1` with
`LWBGT_SKIP_PSYCHROMETRIC_WET_BULB`; see the ABI contract above.

## R

Install from CRAN:

```r
install.packages("lwbgt")
```

If CRAN is unavailable, use R-universe:

```r
install.packages("lwbgt", repos = "https://zyf0717.r-universe.dev")
```

```r
library(lwbgt)

weather <- lwbgt_input(
    year = 2024, month = 4, day = 15, hour = 14, minute = 30,
    gmt_offset_hours = 8, averaging_minutes = 60, urban = 1,
    latitude_deg_north = 1.3521, longitude_deg_east = 103.8198,
    solar_w_m2 = 742.0, pressure_hpa = 1008.4,
    air_temperature_c = 32.1, relative_humidity_percent = 68.0,
    wind_speed_m_s = 2.8, wind_height_m = 10.0,
    vertical_temperature_difference_c = 1
)

result <- calculate(weather)
stopifnot(result$status == 0)
print(result$wbgt_c)
print(esat(273.15, phase = 0))
```

The R API provides `lwbgt_input()`, `calculate()`, and `esat()`. It returns
ordinary data frames, recycles scalar constructor arguments, and isolates
invalid or non-convergent rows. See the
[R quick start](https://github.com/zyf0717/lwbgt/blob/main/r/README.md).

## Julia

Use [LWBGT.jl](https://github.com/zyf0717/LWBGT.jl) with Julia 1.10 or later.
Install from General:

```julia
using Pkg
Pkg.add("LWBGT")
```

```julia
using LWBGT

weather = Input(
    year=2024, month=4, day=15, hour=14, minute=30,
    gmt_offset_hours=8, averaging_minutes=60, urban=1,
    latitude_deg_north=1.3521, longitude_deg_east=103.8198,
    solar_w_m2=742.0, pressure_hpa=1008.4,
    air_temperature_c=32.1, relative_humidity_percent=68.0,
    wind_speed_m_s=2.8, wind_height_m=10.0,
    vertical_temperature_difference_c=1,
)

result = calculate(weather)
@assert result.status == 0
println(result.wbgt_c)

results = calculate_batch([weather, weather])
println(esat(273.15; phase=0))
```

`Input` and `Result` are immutable typed records following the
[ABI contract](https://github.com/zyf0717/lwbgt/blob/main/docs/ABI.md).

## SwiftPM

Add `CLWBGT` as a target dependency in `Package.swift`:

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WeatherService",
    dependencies: [
        .package(url: "https://github.com/zyf0717/lwbgt.git", from: "1.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "WeatherService",
            dependencies: [
                .product(name: "CLWBGT", package: "lwbgt"),
            ]
        ),
    ]
)
```

In `Sources/WeatherService/main.swift`:

```swift
import CLWBGT

var weather = lwbgt_input_v1(
    year: 2024, month: 4, day: 15, hour: 14, minute: 30,
    gmt_offset_hours: 8, averaging_minutes: 60, urban: 1,
    latitude_deg_north: 1.3521, longitude_deg_east: 103.8198,
    solar_w_m2: 742.0, pressure_hpa: 1008.4,
    air_temperature_c: 32.1, relative_humidity_percent: 68.0,
    wind_speed_m_s: 2.8, wind_height_m: 10.0,
    vertical_temperature_difference_c: 1
)
var result = lwbgt_output_v1()
let status = lwbgt_calc_batch_v1(&weather, &result, 1)
precondition(status == Int32(LWBGT_BATCH_OK))
precondition(result.status == 0)
print(result.wbgt_c)
print(esat(273.15, 0))
```

`CLWBGT` exposes the C structs and functions directly. Check both the call's
return code and the result's status. SwiftPM builds the canonical C sources;
downstream consumption is tested on Linux and macOS in release mode.

## Native C

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
cmake --install build --prefix /desired/prefix
```

The install provides static and shared libraries, `lwbgt.h`, CMake package
metadata, and `pkg-config` metadata. CMake consumers can select
`lwbgt::static` or `lwbgt::shared` after `find_package(lwbgt CONFIG REQUIRED)`.
GCC, Clang/AppleClang, and MinGW GCC are supported. MSVC is available for
[experimental compatibility testing](docs/ABI.md#platforms-and-bindings)
with `-DLWBGT_EXPERIMENTAL_MSVC=ON`.

## Documentation

See the [documentation index](https://github.com/zyf0717/lwbgt/blob/main/docs/README.md)
for API contracts, input assumptions, numerical evidence, and release history.
For development and publishing, use [Contributing](https://github.com/zyf0717/lwbgt/blob/main/CONTRIBUTING.md)
and [Releasing](https://github.com/zyf0717/lwbgt/blob/main/docs/RELEASING.md).
