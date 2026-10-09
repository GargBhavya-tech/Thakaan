# Releasing

C, Python, R, and SwiftPM share one version and Git tag. Test and
documentation changes can stay under `Unreleased`; publishing packages requires
a new version. SwiftPM uses the tag directly and builds the C sources.

The Julia binding [LWBGT.jl](https://github.com/zyf0717/LWBGT.jl) and its
`lwbgt_jll` dependency have separate versions and release procedures.

| Workflow | Trigger | Purpose |
|---|---|---|
| [CI](../.github/workflows/ci.yml) | PR updates and merges into `main`; manual dispatch | Native/Swift, Python, and R checks |
| [Release](../.github/workflows/release.yml) | Annotated `v*` tag push | Build/test packages, verify TestPyPI installation, and publish |

Direct pushes to `main` do not trigger validation. See
[Contributing](../CONTRIBUTING.md#ci) for PR events and manual branch selection.

## Prepare

1. Update shared version metadata, `CHANGELOG.md`, `r/NEWS.md`, and
   `tests/RELEASE.md`. For numerical changes, refresh the
   [baseline](../tests/BASELINE.md) and [benchmarks](../benchmarks/README.md).
2. Open a PR with the version bump and release notes. Review the automatic
   **CI** results, then merge into `main` and wait for post-merge validation
   to pass before tagging.

## Publish

Check out the merged release commit, confirm its version, then create and push
an annotated or signed tag:

```sh
release_version=$(awk -F '"' '/^version = / {print $2; exit}' pyproject.toml)
git tag -a "v${release_version}" -m "lwbgt v${release_version}"
git push origin "v${release_version}"
```

The release workflow builds/tests Python and R packages and publishes Python
to TestPyPI. After hash and installation checks, it publishes the same Python
files to PyPI and attaches Python and R distributions to the GitHub release.

Wait for the entire run to succeed. Check Python/R installation using the
[README](../README.md), and require a successful R-universe build of the tagged
commit before announcing its R release. CRAN submission is separate.

Correct defective releases with a new version; never replace published artifacts.

## Service setup

- Configure PyPI and TestPyPI Trusted Publishing for `release.yml` and its environments.
- Point R-universe to this repository with `subdir: "r"` and `branch: "*release"`.
- Keep the default token read-only; publication requests write access only for
  the jobs that need it. Merge decisions remain manual.
