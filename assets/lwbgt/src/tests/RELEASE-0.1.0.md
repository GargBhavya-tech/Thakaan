# v0.1.0 release verification

Historical verification for this version. For current release instructions, see
[Releasing](../docs/RELEASING.md).

The numerical source was frozen after Optimization 3. No Optimization 4 is
included or permitted before v0.1.0.

On 2026-08-18, commit `0b5406ca34d1a5ec65ea697ceeee9ba767b86e4b`
was cloned locally with `git clone --no-hardlinks` into a separate clean
checkout. The checkout reported no tracked or untracked changes before the
release build.

After the release documentation was assembled, finalized commit
`1db2c56943ae793dfdac4520b84d05bfef6c64ae` was cloned into a second clean
checkout. The complete build, CTest, and explicit differential test were
repeated successfully. The subsequent evidence-only commit changes no library
or test code.

Both CTest tests and the explicit differential run passed. See
[BASELINE.md](BASELINE.md) for the current oracle and [API.md](API.md) for
API verification.
