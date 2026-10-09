# Contributing

Keep changes small, readable, and scientifically justified. Explain the problem,
resulting behavior, and validation. Record units, timestamp conventions,
numerical assumptions, and data provenance where relevant.

## Commits

Use Conventional Commits: `<type>[optional scope]: <description>`.
Common types are `fix`, `feat`, `test`, `perf`, `docs`, `refactor`, `ci`, and
`chore`. Keep each commit focused and use a short imperative description:

```text
test(numerics): cover adjacent float wind thresholds
docs: explain oracle comparison limits
```

Describe rationale and validation in the body when needed. Mark breaking
changes with `!` or a `BREAKING CHANGE:` footer and follow the
[ABI contract](docs/ABI.md).

## Numerical changes

Preserve the v1 calculation, native floating-point flags, intermediate rounding,
and [compatibility policy](docs/COMPATIBILITY.md). Do not edit the retained
oracle, `upstream/wbgt.c.original`.

Add regression cases and compare statuses and output bits in matched builds.
Rejected solar and non-finite weather inputs need separate API checks; see
[tests/BASELINE.md](tests/BASELINE.md). Extend the shared corpus in
`tests/generate_cases.py` and refresh `tests/baseline.json`. Rerun throughput
measurements when the corpus changes. Keep the R copies of `wbgt.c` and
`lwbgt.h` byte-identical to their canonical sources.

## CI

| Workflow | Role |
|---|---|
| [CI](.github/workflows/ci.yml) | PR, post-merge, and manual entrypoint; runs all 15 checks |
| [Native checks](.github/workflows/native.yml) | Reusable C/C++ and SwiftPM build, numerical, ABI, and installation checks |
| [Python package](.github/workflows/python.yml) | Reusable wheel, source-distribution, and installed-package checks |
| [R package](.github/workflows/r.yml) | Reusable compiler/platform and R-release/R-devel package checks |
| [Release](.github/workflows/release.yml) | Tag-triggered Python/R builds and publication |

**CI** runs native/Swift, Python, and R checks when a PR targeting `main`
is opened, updated with new commits, or reopened, and again after it is merged.
PR checks test GitHub's merge ref; the post-merge run tests the merged commit.
Closing a PR without merging skips all validation jobs. Direct branch pushes,
including pushes to `main`, do not trigger validation unless they update an
open PR targeting `main`.

To validate a selected branch manually, use **Actions → CI → Run workflow**.
Manual runs test the selected commit. Review the tested commit and results in
Actions before merging. A new run cancels older validation for the same PR or
manually selected branch.

Validation does not write commits or publish. See [RELEASING.md](docs/RELEASING.md)
for release preparation and tag-triggered publication.

The Linux Clang / R-devel job selects its compiler through
[Makevars.clang](.github/r/Makevars.clang), using `R_MAKEVARS_USER`.

```sh
python3 tests/check_versions.py
python3 tests/check_r_sources.py
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```

For Python changes, build and install a wheel in a clean environment, then run
`python -m unittest discover -s tests/python -v`. For R changes, run
`R CMD build r` and `R CMD check` on the archive. Run downstream SwiftPM tests
for C interface or packaging changes, including the native numerical comparison:

```sh
build/lwbgt_probe build/cases.csv > build/swift-expected.csv
LWBGT_CASES="$PWD/build/cases.csv" \
LWBGT_EXPECTED="$PWD/build/swift-expected.csv" \
swift test --package-path tests/swiftpm -c release -Xcc -march=native
```

Performance claims require the [benchmark method](benchmarks/README.md).
Windows CI also tests an opt-in MSVC build (`LWBGT_EXPERIMENTAL_MSVC=ON`) and
uploads cross-compiler numerical reports. Finite values must match exactly;
NaN payload differences are allowed. Inspect those reports before claiming
compatibility beyond the tested corpus and platform.
Report the compiler/platform and any checks you could not run in the PR.
Follow [RELEASING.md](docs/RELEASING.md) for publication.
