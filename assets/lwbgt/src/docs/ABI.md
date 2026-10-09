# ABI contract

The public interface is `include/lwbgt.h`. The shared library exports
`calc_wbgt`, `esat`, `lwbgt_calc_batch_v1`, and `lwbgt_calc_batch_ex_v1`.
Only declarations in this header are supported; numerical helpers have internal
linkage. The header supports C and C++.

## Versioning

`LWBGT_VERSION_MAJOR/MINOR/PATCH` identify the package release;
`LWBGT_FFI_ABI_VERSION` identifies the structure and batch ABI. These versions
are independent of the numerical calculation version.

The v1 symbols, layouts, and default calculation remain available throughout
1.x. Incompatible interfaces or changes to defined valid-input results require
new versioned entrypoints. Do not reorder, resize, remove, or repurpose fields.
See [the compatibility policy](https://github.com/zyf0717/lwbgt/blob/main/docs/COMPATIBILITY.md).

## Data model and layout

The ABI requires 8-bit bytes, 32-bit `int32_t` and `float`, and 64-bit `double`.
Builds enforce structure sizes and boundary offsets with compile-time assertions.

### `lwbgt_input_v1`

Total size: 104 bytes. Integer fields occupy the first 32 bytes; every floating
point input is an ABI-boundary `double`.

| Offset | Field | Type | Units and meaning |
|---:|---|---|---|
| 0 | `year` | `int32_t` | Four-digit Gregorian year; solar-position support is 1950–2049 inclusive |
| 4 | `month` | `int32_t` | Month 1–12; 0 means `day` is day-of-year |
| 8 | `day` | `int32_t` | Day of month, or day-of-year when `month == 0` |
| 12 | `hour` | `int32_t` | Local standard-time hour, 0–23 |
| 16 | `minute` | `int32_t` | Minutes past the hour |
| 20 | `gmt_offset_hours` | `int32_t` | Local standard time minus GMT, in hours |
| 24 | `averaging_minutes` | `int32_t` | Input averaging interval in minutes |
| 28 | `urban` | `int32_t` | 0 selects rural; 1 selects urban wind scaling |
| 32 | `latitude_deg_north` | `double` | Degrees north in [-90, 90] |
| 40 | `longitude_deg_east` | `double` | Degrees east in [-180, 180] |
| 48 | `solar_w_m2` | `double` | Solar irradiance, W/m² |
| 56 | `pressure_hpa` | `double` | Barometric pressure, hPa (equivalent to mb) |
| 64 | `air_temperature_c` | `double` | Dry-bulb air temperature, °C |
| 72 | `relative_humidity_percent` | `double` | Relative humidity, percent |
| 80 | `wind_speed_m_s` | `double` | Wind speed, m/s |
| 88 | `wind_height_m` | `double` | Wind measurement height, m |
| 96 | `vertical_temperature_difference_c` | `double` | Upper-minus-lower temperature difference, °C; only whether it is `< 0` or `>= 0` is used. It can affect results only when `urban == 0`, it is nighttime, `wind_height_m != 2`, and `wind_speed_m_s < 2.5` |

### `lwbgt_output_v1`

Total size: 24 bytes.

| Offset | Field | Type | Meaning |
|---:|---|---|---|
| 0 | `status` | `int32_t` | Per-record scalar solver status |
| 4 | `estimated_wind_speed_m_s` | `float` | Effective wind speed at 2 m, m/s |
| 8 | `globe_temperature_c` | `float` | Globe temperature, °C |
| 12 | `natural_wet_bulb_c` | `float` | Natural wet-bulb temperature, °C |
| 16 | `psychrometric_wet_bulb_c` | `float` | Psychrometric wet-bulb temperature, °C |
| 20 | `wbgt_c` | `float` | Outdoor wet-bulb globe temperature, °C |

## Batch call contract

```c
int lwbgt_calc_batch_v1(
    const lwbgt_input_v1 *inputs,
    lwbgt_output_v1 *outputs,
    size_t count
);
```

- With `count == 0`, return `LWBGT_BATCH_OK`; either pointer may be null.
- Otherwise, null input or output returns `LWBGT_BATCH_INVALID_ARGUMENT`
  without modifying outputs.
- The caller owns both arrays and supplies at least `count` elements. Input
  and output storage must not overlap.
- Rows execute serially in input order through the scalar calculation.
  The function return reports call validity; each row has its own `status`.
- Row status is 0 on success or -1 for solar-position rejection or failure of
  the globe or natural wet-bulb solver to converge.
- Solar rejection sets all five numerical outputs to -9999. On solver
  non-convergence, the failed temperature and WBGT are -9999; other outputs
  may remain valid.
- Native inputs are forwarded without additional validation, unit conversion,
  clamping, or missing-data policy beyond the scalar model.
- At 2 m wind height, estimated wind is the supplied speed rounded to `float`.

### Optional psychrometric wet-bulb (since 1.2.0)

The default entrypoint continues to calculate every output. The extended
entrypoint uses the same v1 structures and adds flags:

```c
int lwbgt_calc_batch_ex_v1(
    const lwbgt_input_v1 *inputs,
    lwbgt_output_v1 *outputs,
    size_t count,
    uint32_t flags
);
```

With `flags == 0`, results are identical to `lwbgt_calc_batch_v1`. Set
`LWBGT_SKIP_PSYCHROMETRIC_WET_BULB` to omit that independent solve. Its output
field is always `-9999`; row status and all other output fields are unchanged.
WBGT depends on the natural wet-bulb and globe temperatures, not the
psychrometric wet-bulb temperature. Unknown flag bits return
`LWBGT_BATCH_INVALID_ARGUMENT` without modifying outputs, including when
`count == 0`. With valid flags, the existing pointer, ordering, ownership,
and failure contracts apply.

## Scalar calls

`calc_wbgt` accepts scalar floating-point inputs as `double` and rounds them
at entry to `float`, matching the original K&R argument promotions. Outputs
are `float *`. Local standard time is converted to GMT; interval centering
subtracts half of `averaging_minutes`. Its 2 m wind output is now assigned; the original left it
unwritten. Solar-position support remains 1950–2049 with the original date
arithmetic. Unsupported years return -1 and initialized failure outputs.
Passing `NULL` for the psychrometric wet-bulb output skips that solve; the other
four scalar output pointers must be non-null. Supplying all five pointers
preserves the default calculation.

`esat` accepts temperature in kelvin and returns saturation pressure in hPa.
Phase 0 selects liquid water; phase 1 selects ice. Other phases are unsupported.

## Ownership and concurrency

Independent scalar and batch calls are thread-safe with separate output
buffers. A batch call is serial. The API performs no allocation and retains no
input or output pointers.

## Platforms and bindings

GCC, Clang/AppleClang, and MinGW GCC are supported. MSVC builds are experimental
and require `-DLWBGT_EXPERIMENTAL_MSVC=ON` with CMake 3.21 or newer. CMake and
SwiftPM build the
production sources as C11; R uses its configured C dialect. Only the retained
original test oracle builds in GNU89 mode. Linux keeps SONAME `liblwbgt.so.0`
while the ABI is unchanged. CI tests native builds and installed C/C++ consumers.

CMake and R disable fast math and floating-point contraction. The kernel also
disables contraction through a Clang source pragma for SwiftPM, without unsafe
package flags, and rejects fast-math or finite-math-only compilation. Custom
builds must preserve these settings; `-ffp-contract=fast` can override the Clang
pragma. Compiler, architecture, and math-library differences can still affect
output bits; see [COMPATIBILITY.md](COMPATIBILITY.md).

The Windows CI job tests MSVC with `/fp:strict`, including static and DLL APIs,
exports, installed C/C++ consumers, and a Python wheel. MinGW first verifies
the retained GNU89 oracle; cross-compiler reports then measure differences on
the same runner. Status or finite/non-finite/failure-sentinel changes fail the
comparison, as do finite numerical differences. Signed zeros compare equal;
NaN payload differences are reported but allowed. The POSIX benchmark harness
and original oracle are not compiled by MSVC.

| Binding | Behavior |
|---|---|
| Python | `Input` and `Result` map to the v1 structures. Both calculation functions use the extended batch ABI with keyword-only `psychrometric=True` by default; `False` returns `-9999` for that field. `esat` calls the scalar symbol. The bundled runtime loads through `ctypes` and `importlib.resources`. No extra input or failure policy. |
| R | Compiles synchronized kernel sources and calls the scalar API through `.Call`. `calculate(input, psychrometric=TRUE)` includes the independent solve by default; `FALSE` returns `NA` for that column. Adds recycling, validation, R-specific statuses, warnings, and `NA` substitution; see the [R quick start](https://github.com/zyf0717/lwbgt/blob/main/r/README.md). |
| Julia | [LWBGT.jl](https://github.com/zyf0717/LWBGT.jl) maps immutable `Input` and `Result` records to the v1 structures and loads the native library through `lwbgt_jll`. Both calculation functions use the serial batch ABI; `esat` calls the scalar symbol. No extra input or failure policy. |
| SwiftPM | `CLWBGT` builds the canonical C sources and exposes the header directly. It is a C-library product, not a Swift wrapper. |
