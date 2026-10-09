# v1.1.0 release verification

[v1.1.0](https://github.com/zyf0717/lwbgt/releases/tag/v1.1.0) was published on
2026-10-02 from commit `b6c49eff1d34738ae40f1d6b51a62cc3a5d0e83d`.
The reference-compatible numerical kernel, v1 FFI ABI, and `liblwbgt.so.0`
SONAME are unchanged.

The CMake package now accepts compatible releases within major version 1. Its
existing consumer test requests 1.0.0 and must continue to accept 1.1.0.

## Validation status

The [release workflow](https://github.com/zyf0717/lwbgt/actions/runs/36971287469)
passed, including Python/R package checks and publication.

Local Linux checks passed for the 1.1.0 preparation:

- Version coherence and byte-identical R source copies.
- All 15 native CTest checks, including the installed CMake consumer requesting
  1.0.0, using GCC 13.3 in release mode.
- All four SwiftPM consumer tests using the CI image `swift:6.2.4-noble` in
  release mode with `-march=native`, including bit-for-bit comparison with CMake
  over all 35,976 WBGT cases.
- Python wheel and source-distribution contents, plus all 18 installed-wheel
  tests with Python 3.14.7.
- `R CMD build` and `R CMD check`, including the PDF manual: `Status: OK`.

The numerical corpus and recorded baseline are unchanged; retain their original
version and provenance in [baseline.json](baseline.json). See
[BASELINE.md](BASELINE.md) for coverage and
[benchmarks/README.md](../benchmarks/README.md) for the existing throughput record.
No new numerical or performance claim is introduced by this version bump.

Follow [RELEASING.md](../docs/RELEASING.md) for preparation and publication.
Earlier verification is preserved in [RELEASE-1.0.1.md](RELEASE-1.0.1.md) and the
other `RELEASE-*.md` files.
