# Compatibility and performance

The v1 calculation remains the default throughout 1.x. Changes to defined
valid-input numerical results require explicit versioned APIs, with v1 still
available. Package versions and FFI ABI versions are independent.

## Numerical comparison

Under matched compiler and floating-point settings, 35,976 WBGT cases match
the retained original C bit for bit for `Tg`, `Tnwb`, `Tpsy`, WBGT, and `esat`.
Estimated wind also matches except for direct scalar calls at 2 m: the
original left that output unwritten; `lwbgt` writes the supplied speed rounded
to `float`. Batch and R callers already initialized that output.

The suite covers every supported year, targeted branches, and 10,000 seeded
inputs. Separate tests cover 1,682 water/ice `esat` cases and 996 internal
branch diagnostics. [tests/BASELINE.md](../tests/BASELINE.md) records ranges,
seeds, convergence counts, hashes, and reproduction commands.

These are sampled checks, not an all-input or all-platform guarantee.
Non-finite weather inputs have separate API consistency tests; their outputs
can differ from the original. See [DEVIATIONS.md](DEVIATIONS.md). The original
1950–2049 year guard and original date arithmetic are retained.

## Performance

Throughput comparisons use the same 35,976-row corpus at 1× and 10×:
35,976 and 359,760 scalar calls per measured run. All rows are timed,
including convergence failures. The current kernel measured about
1.45–1.67× throughput on GCC 13.3.0 across CPUs 0 and 12. Results
depend on hardware, compiler, and workload; these synthetic inputs are not
observed weather.

[benchmarks/README.md](../benchmarks/README.md) records the method and results.
[ABI.md](ABI.md) defines the public layouts, symbols, and API guarantees.
