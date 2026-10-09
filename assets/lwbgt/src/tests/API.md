# API verification

[docs/ABI.md](../docs/ABI.md) defines the supported symbols, layouts, units,
status codes, and ownership rules. Tests verify that contract:

| Check | Coverage |
|---|---|
| `shared_exports`, `python_runtime_exports` | Only `calc_wbgt`, `esat`, and `lwbgt_calc_batch_v1` are exported by shared runtimes |
| `installed_consumer` | Installed C/C++ headers, structure sizes/offsets, linkage, SONAME, and accompanying documents |
| Whole-corpus compatibility probes | Status and output bits against the retained original through static and Python-runtime libraries |
| Water/ice probes | Both `esat` phases, rounding boundaries, and non-finite temperatures |
| Internal branch diagnostics | Stability classes and adjacent horizon/clipping inputs; helpers remain unsupported API |
| Batch tests | Scalar equality in chunks of up to 1,024 rows, null arguments, and deterministic solar rejection |
| Installed-wheel tests | Resource loading, ABI layouts, public Python API, and full WBGT/invalid-weather batch results |
| R package checks | Vectorized inputs, row statuses, `NA` handling, and installed native symbols |
| SwiftPM consumer tests | ABI layout, batch contract, and bit-for-bit comparison with the native WBGT corpus |
| `benchmark_accounting` | Successful and failing rows at 1×/10×, exact call counts, checksum consumption, rejected scales, and comparison reports |
| `demo_smoke` | Optional demonstration on valid input |

At 2 m height, compatibility checks require the supplied wind rounded to
`float`, including heights that round to 2 m. The original left that output
unwritten. Rejected solar inputs and non-finite weather have separate current-API
checks. [BASELINE.md](BASELINE.md) records corpora, hashes, and limitations.

The static archive retains internal helpers for solar geometry, wind scaling,
and thermophysical properties. Their visibility does not make them supported
entrypoints. Library builds omit the demonstration `main` and its I/O dependencies.
