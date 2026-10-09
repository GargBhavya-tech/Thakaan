# v0.4.2 release verification

Historical verification for this version. For current release instructions, see
[Releasing](../docs/RELEASING.md).

v0.4.2 adds a dependency-safe SwiftPM C-library product backed directly by the
canonical kernel sources and public header. The C ABI remains version 1.

Release acceptance requires:

- the full native CTest matrix and numerical equivalence suite;
- release-mode downstream SwiftPM consumer tests on Linux and macOS;
- all installed-wheel, sdist, ABI, resource, and version-coherence tests;
- base-R scalar, vector, recycling, empty, validation, failure-status, schema,
  and water/ice `esat` tests;
- byte-identical canonical and `r/src` kernel/header sources;
- registered forced R symbols, hidden internal symbols, and no compiled
  demonstration `main`, `printf`, or `exit` dependency;
- complete `R CMD check` runs on Linux/GCC, Linux/Clang with R-devel,
  Windows/Rtools, and macOS/AppleClang, with the PDF manual checked on Linux;
- installed legal files and no errors or warnings;
- successful installation from the GitHub release and from R-universe; and
- an R-universe build whose source commit matches the GitHub release tag.

The signed or annotated Git tag is created only after the source commit passes
all local and CI gates. The tag workflow publishes the immutable GitHub
release; the `*release` entry in `zyf0717/zyf0717.r-universe.dev` then makes
that release available through R-universe. A required source change after
publication requires a unified version bump and a new release.

Local release verification on 2026-09-22 passed version and R-source coherence,
the full native CTest suite, the Linux Swift 6.2.4 downstream-consumer tests,
sdist content and metadata checks, all 17 tests against a source-built wheel,
and `R CMD check` without the optional manual and vignette stages. macOS
SwiftPM, full cross-platform wheel and R checks, direct GitHub installation,
and R-universe results remain pending until their CI or publication gates run.

Historical verification is retained in `RELEASE-0.1.0.md`,
`RELEASE-0.2.0.md`, and `RELEASE-0.2.1.md`.
