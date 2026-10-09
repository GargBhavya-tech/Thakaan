# v1.0.1 release verification

Historical verification for this version. For current release instructions, see
[Releasing](../docs/RELEASING.md).

v1.0.1 relaxes R golden-value tests to native binary32 epsilon (`2^-23`) for
builds without long-double support. The calculation and public interfaces are
unchanged. On 2026-10-01, native, installed-wheel Python, and SwiftPM checks
passed. R 4.6.1 checks passed with GCC, Clang, and disabled long-double support,
including the PDF manual, with no errors or warnings.

## Unreleased validation

The shared 35,976-row corpus, expanded edge checks, throughput comparison, and
documentation updates retain package version 1.0.1. They require no separate
release. [BASELINE.md](BASELINE.md) records coverage and hashes;
[benchmarks/README.md](../benchmarks/README.md) records the timing method and
results.

Local checks on 2026-10-01 passed:

- 17 CTest checks on Linux x86_64 with GCC 13.3.0 and Clang 18.1.3, including
  whole-corpus compatibility, scalar/batch equality, and benchmark accounting;
- 18 installed-wheel Python tests, including a wheel built from the sdist;
- three release-mode SwiftPM tests with Swift 6.2.4;
- distribution, version, and R-source checks;
- seven paired throughput measurements at each scale with GCC 13.3.0,
  measuring 1.605× and 1.604× median speedups at 1× and 10×.

The extracted Clang toolchain needed its `libomp` directory in
`LD_LIBRARY_PATH` for the R example. Follow
[RELEASING.md](../docs/RELEASING.md) when preparing a future release.

## Earlier verification

v1.0.0, checked on 2026-09-25, passed native, distribution, installed-wheel,
R, and Linux SwiftPM checks. Numerical results matched the pre-refactor kernel,
the Linux SONAME remained `liblwbgt.so.0`, and R reported `Status: OK`.
Older records are retained in the `RELEASE-*.md` files.
