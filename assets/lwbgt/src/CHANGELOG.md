# Changelog

## Unreleased

## v1.2.0 — 2026-10-09

- Allow explicitly skipping psychrometric wet-bulb: a null scalar output,
  `LWBGT_SKIP_PSYCHROMETRIC_WET_BULB` in the new `lwbgt_calc_batch_ex_v1`, or
  `psychrometric=False` / `FALSE` in Python / R. Existing defaults, v1 layouts,
  and retained numerical results are preserved.
- Give numerical helpers internal linkage and use a private saturation-pressure
  helper so the compiler can inline and specialize internal calls. Undocumented
  helper symbols are no longer linkable from static-library consumers.
- Change the internal R native calculation routine to take two arguments;
  existing calls to the exported R functions remain compatible.
- Extend the synthetic throughput benchmark with psychrometric opt-out.
- Document the separately released LWBGT.jl binding, General installation,
  and Julia usage of the shared native kernel.
- Name the CI entrypoint `ci.yml` and the reusable native and Python workflows
  `native.yml` and `python.yml`. Group the R compiler configuration under
  `.github/r/` and document workflow roles and entrypoints.
- Run validation automatically on PR updates and merges into `main`, with
  manual dispatch available. Direct pushes to `main` do not trigger validation;
  preserve tag-triggered publication.
- Limit in-repository interfaces to C/FFI, Python, R, and SwiftPM. Simplify
  validation and release packaging around those interfaces. Julia development
  installs using the removed `julia/` directory must migrate to LWBGT.jl.

## v1.1.0 — 2026-10-02

- Accept compatible 1.x releases in the installed CMake package configuration;
  preserve the C ABI and shared-library SONAME.
- Add opt-in MSVC compatibility testing for native libraries, installed C/C++
  consumers, Python loading, and exact finite cross-compiler comparisons.
- Reuse the Linux native build for SwiftPM, combine Linux/R-release checking
  with the complete source-package check, and avoid retesting Python 3.10 wheels.
- Build production CMake and SwiftPM sources as C11; retain GNU89 for the
  original test oracle. Preserve SwiftPM floating-point rounding without unsafe
  dependency flags, reject fast-math builds, and test native Linux Clang in CI.
- Compare SwiftPM with CMake bit for bit on the full WBGT corpus with native
  CPU targeting enabled on Linux and macOS.
- Move the canonical Argonne license beside the C kernel in `src/`; keep
  shipping it in Python and native distributions and retain the R package copy.
- Use one 35,976-row retained-original WBGT corpus, including 10,000 seeded
  nominal/stress cases, with per-cohort convergence counts and output hashes.
- Check adjacent binary32 wind/radiation thresholds, wind-height rounding,
  inversion signs, polar and horizon geometry, radiation clipping, UTC and
  averaging rollover, calendar forms, and extreme thermophysical inputs.
- Add 996 internal branch diagnostics and 1,682 water/ice saturation-pressure
  cases, including rounding boundaries and non-finite temperatures.
- Run the corpus through native and Python runtime libraries,
  scalar/batch APIs, and the installed Python wrapper. Expand deterministic
  rejected-solar-input checks and add 64 invalid/non-finite weather consistency
  cases. Preserve the numerical kernel and ABI.
- Compare whole-corpus throughput at 1× and 10×, including failing
  rows, with interleaved timings, call counts, and retained results.
- Streamline project documentation and release guidance; document contribution
  checks and Conventional Commits in `CONTRIBUTING.md`.
- Remove redundant standalone binding examples and their CMake tests; use the
  Python and R packages and their dedicated CI for language bindings.
- Keep Python scalar binding setup in its regression test and remove repeated
  release numbers from Python and Swift tests.

## v1.0.1 — 2026-10-01

- Use native single-precision tolerance for R test-suite golden values on
  builds without long-double support.

## v1.0.0 — 2026-09-25

- Stabilized the existing reference-compatible v1 calculation and public C/FFI,
  Python, R, and SwiftPM interfaces without changing numerical results.
- Committed to explicit versioned APIs for future changes to valid-input
  numerical results. The v1 calculation remains the default in the 1.x series.
- Kept the shared library's existing SONAME because the C ABI is unchanged.
- Shared atmospheric and wet-bulb intermediate values in the C kernel while
  preserving reference-compatible v1 results; simplified the source
  deviation notes and documented pinned-core throughput against the original.

## v0.4.3 — 2026-09-23

- Corrected direct scalar `calc_wbgt` output at 2 m to write the supplied wind
  speed and initialized the optional demonstration's first `dT` argument.
- Added an upstream deviation register and expanded the retained-oracle
  comparison across all supported years and changed numerical
  paths, while making the scalar wind-output exception explicit.

## v0.4.2 — 2026-09-22

- Added a dependency-safe SwiftPM `CLWBGT` C-library product backed directly
  by the canonical native sources and public header.
- Added release-mode downstream SwiftPM consumer tests on Linux and macOS.

## v0.4.1 — 2026-09-19

- Declared the CRAN-facing R package license as standard Apache License 2.0.

## v0.4.0 — 2026-09-18

- Added a dependency-free R package under `r/` with the plain-data-frame
  `lwbgt_input()`, `calculate()`, and vectorized `esat()` API.
- Matched the Python wrapper's input names, units, ordering, and public `esat`
  default while adding per-row R validation and stable failure statuses.
- Compiled synchronized kernel sources directly into the R DLL with registered,
  forced native symbols and the native floating-point safety flags.
- Guarded the legacy demonstration program out of library builds and made
  invalid solar-position inputs return initialized failure outputs.
- Added base-R API tests, source/version coherence gates, multi-platform R
  package checks, direct GitHub installation, and R-universe distribution.

## v0.3.0 — 2026-08-19

- Added the official dependency-free Python API: immutable `Input` and `Result`
  records plus `calculate`, `calculate_batch`, and `esat`.
- Added an unversioned, wheel-only shared target built from the existing native
  objects with the same restricted three-symbol export surface.
- Added platform-specific, Python-ABI-independent wheel and rebuildable sdist
  packaging through scikit-build-core.
- Added installed-wheel ABI layout, resource loading, scalar, batch, failure,
  `esat`, deterministic fixture, archive licensing, and version-coherence tests.
- Added cibuildwheel coverage for manylinux x86_64/aarch64, macOS x86_64/arm64,
  and Windows amd64, plus OIDC Trusted Publishing release automation.

## v0.2.1 — 2026-08-18

- Licensed project-authored files under Apache-2.0.
- Added the authoritative v1 ABI contract covering layouts, units, status and
  ownership semantics, concurrency, symbols, and compatibility policy.
- Added an installed C++ consumer that validates public-header compatibility,
  structure sizes and offsets, and shared-library linkage.

## v0.2.0 — 2026-08-18

- Added the fixed-layout `lwbgt_input_v1` and `lwbgt_output_v1` structures and
  serial `lwbgt_calc_batch_v1` FFI entry point without changing the scalar ABI.
- Added versioned shared-library builds with a three-symbol dynamic export
  surface while retaining the existing static archive.
- Added dependency-light Python and R examples and made them release
  gates on Linux/GCC, macOS/Clang, and Windows/MinGW CI.
- Added relocatable CMake and `pkg-config` installation metadata.
- Verified the final static and shared artifacts on Linux/GCC,
  macOS/AppleClang, and Windows/MinGW, including installed consumers and all
  language examples.
- Revalidated the position-independent static build with exact compatibility
  and all release gates passing.

## v0.1.0 — 2026-08-18

The release gate passed with exact compatibility. The current oracle and
benchmark are recorded in
[`tests/BASELINE.md`](tests/BASELINE.md) and
[`benchmarks/README.md`](benchmarks/README.md).

- Pinned Liljegren WBGT v1.1 commit
  `cd672a886880b67f3f27bdbf75038d8f7ff0bac2` and source blob
  `7bc6e6ddd76a538d6454b27e9b252667846e6c9b`.
- Added static `liblwbgt.a` and the permanent `calc_wbgt`/`esat` compatibility
  declarations in `lwbgt.h`.
- Skipped radiative work that was multiplied by zero in the psychrometric
  wet-bulb solve.
- Reused rounded air viscosity in cylinder and sphere convective coefficients.
- Hoisted invariant atmospheric, surface, and solar radiation terms from the
  iterative globe and natural wet-bulb solves after profiling identified both
  solves as the remaining hot paths.
- Verified exact output equality against the original source with
  GCC 13.3.0 and GCC 16.2.0.
- Audited the static archive export surface and fixed the permanent supported
  API at `calc_wbgt` and `esat`; inherited helper exports remain implementation
  details.
