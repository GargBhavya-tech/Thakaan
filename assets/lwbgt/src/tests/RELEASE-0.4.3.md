# v0.4.3 release verification

Historical verification for this version. For current release instructions, see
[Releasing](../docs/RELEASING.md).

v0.4.3 corrects direct scalar estimated wind at the 2 m reference height and
initializes the optional demonstration's first `dT`. The original 1950–2049
solar calculation and its valid-input numerical results remain unchanged,
apart from that scalar wind output. The native ABI remains version 1.

Local verification on 2026-09-23 passed:

- `tests/check_versions.py` and `tests/check_r_sources.py`;
- the Release CMake build and all 12 CTest tests, including both
  compatibility comparisons and installed consumers;
- Python source distribution and wheel content checks, `twine check`, a wheel
  rebuilt from the source distribution, and all 17 tests against that wheel in
  a clean environment;
- `R CMD build r` and the complete `R CMD check` with PDF manual: `Status: OK`;
- the downstream SwiftPM consumer in release mode with Swift 6.2.4 on Linux:
  all three tests passed.

Release acceptance also requires green native, SwiftPM, wheel, and R CI on the
exact candidate commit. The annotated tag and publication follow those gates;
they have not been performed for this candidate.

The previous release's verification record is retained in
`RELEASE-0.4.2.md`.

The v0.4.3 tag and GitHub release were subsequently published. Native, wheel,
R, and release workflows succeeded on commit `fd7d2e9`.
