# v0.2.0 release verification

Historical verification for this version. For current release instructions, see
[Releasing](../docs/RELEASING.md).

v0.2.0 leaves the frozen numerical source unchanged and adds a versioned batch
FFI, static/shared installation metadata, and tested Python and R
interoperability examples.

On 2026-08-18, release-preparation commit
`cd7f986f16c8bcddea34e03a4d7580fa2acad7e5` was cloned locally with
`git clone --no-hardlinks` into a separate directory. The checkout reported no
tracked or untracked changes before configuration.
The subsequent evidence-only commit changes no library, binding, build, or test
code.

All eight CTest tests passed, including the three-symbol shared export audit,
both installed CMake consumers, and the Python and R examples. The
explicit differential run passed. [BASELINE.md](BASELINE.md) records the
current oracle.

The same library and test tree passed GitHub Actions on Linux/GCC,
macOS/AppleClang, and Windows/MinGW in
[run 32108617717](https://github.com/zyf0717/lwbgt/actions/runs/32108617717).
The workflow covered configuration, static and shared builds, all eight CTest
tests, installed consumers, and language examples.

The v0.1.0 clean-checkout record is retained in
`RELEASE-0.1.0.md`.
