# Documentation

Start with the [README](../README.md) for installation and language entrypoints.

| Document | Purpose |
|---|---|
| [ABI contract](ABI.md) | Fields, units, layouts, statuses, ownership, and concurrency |
| [Input assumptions](INPUTS.md) | Input sourcing and conditional fallbacks |
| [Compatibility](COMPATIBILITY.md) | Numerical guarantees and evidence |
| [FAQ](FAQ.md) | Integration and failure handling |
| [R guide](../r/README.md) | R installation and row-level failure handling |
| [Julia guide](https://github.com/zyf0717/LWBGT.jl#readme) | Julia installation and usage through LWBGT.jl |
| [Package comparison](COMPARISON.md) | lwbgt, pywbgt, and thermofeel scope |
| [Upstream](UPSTREAM.md) | Pinned source provenance |
| [Source changes](DEVIATIONS.md) | Changes from the retained original |
| [Contributing](../CONTRIBUTING.md) | Validation and Conventional Commits |
| [Releasing](RELEASING.md) | Release gates and publication |
| [Changelog](../CHANGELOG.md) | Release history |
| [Numerical baseline](../tests/BASELINE.md) | Corpus, seeds, hashes, and convergence |
| [API verification](../tests/API.md) | Tests of the public ABI and packaging contract |
| [Release verification](../tests/RELEASE.md) | Evidence for the current release |
| [Benchmark](../benchmarks/README.md) | Timing method and results |

Versioned `tests/RELEASE-*.md` files are historical validation records. Their
commands and release gates apply to those versions; use [Releasing](RELEASING.md)
for the current procedure.
