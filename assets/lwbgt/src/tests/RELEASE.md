# v1.2.0 release verification

Prepared on 2026-10-09; publication is pending. This release adds psychrometric
wet-bulb opt-out and private numerical helpers while preserving the public v1
ABI, default calculation, and `liblwbgt.so.0` SONAME.

## Compatibility

- Existing calls through the public C, Python, R, and SwiftPM APIs remain compatible.
  Psychrometric wet-bulb remains enabled unless explicitly disabled.
- The new `lwbgt_calc_batch_ex_v1` accepts flags using the existing 104-byte
  input and 24-byte output structures. Zero flags preserve all outputs.
- Undocumented C helpers are now private. R's internal native calculation
  routine takes two arguments. Consumers of these internals must update.
- Julia development installs tracking this repository's removed `julia/`
  directory must migrate to the separately maintained LWBGT.jl package.

## Validation status

Local Linux checks passed on 2026-10-09:

- Version coherence and byte-identical R source copies.
- All 15 native CTest checks with GCC 13.3.0 and Clang 18.1.3, including
  numerical regression, opt-out equivalence, exports, and installed consumers.
- A C consumer compiled against 1.1.0 headers and library runs successfully
  with the 1.2.0 shared library, retaining SONAME `liblwbgt.so.0`.
- Python 3.12 wheel built from the source distribution, archive content checks,
  and all 20 installed-wheel tests in a clean environment.
- All four SwiftPM consumer tests with `swift:6.2.4-noble`, release mode and
  `-march=native`, including bitwise comparison over all 35,976 corpus rows.
- R 4.6.1 source-package build and full check, including the PDF manual:
  `Status: OK`.

Cross-platform CI and post-merge validation are required before tagging.
Publication is pending.

The synthetic corpus and [baseline.json](baseline.json) are unchanged;
retain their original provenance. [BASELINE.md](BASELINE.md) defines coverage
and limits. [benchmarks/README.md](../benchmarks/README.md) records refreshed
throughput results using the same corpus, including psychrometric opt-out.

Follow [RELEASING.md](../docs/RELEASING.md) for validation and publication.
Previous verification is preserved in [RELEASE-1.1.0.md](RELEASE-1.1.0.md).
