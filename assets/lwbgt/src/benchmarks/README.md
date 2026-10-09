# Throughput benchmark

Compare the retained original and current scalar kernels using the same
35,976-row corpus as the numerical tests, at 1× and 10×.

Each scale repeats the complete corpus in order. All rows are timed, including
3,076 failures per corpus pass. This measures kernel execution; CSV loading,
one full-corpus warm-up per execution, Python overhead, and result formatting
are excluded. It does not measure batch or language-wrapper throughput.

The C harness consumes every output bit through a volatile integer checksum,
uses a monotonic clock, and pins to the first available CPU on Linux. The
runner records CPU affinity (`-1` if unavailable), rotates kernel order for
seven repetitions at each scale, and reports median elapsed time, rows/s, and
relative median absolute deviation. Reports retain raw elapsed times, call and
failure counts, compiler/platform details, and corpus/executable hashes.

## Results

On 2026-10-09, GCC 13.3.0 on Linux x86_64 (7.0.0-34-generic, glibc 2.39),
Intel Core i9-13900HK, pinned to CPU 0:

| Scale | Calls/run | Original s | Current s | Original rows/s | Current rows/s | Speedup |
|---|---:|---:|---:|---:|---:|---:|
| 1× | 35,976 | 0.306 | 0.210 | 117,611 | 171,000 | 1.454× |
| 10× | 359,760 | 3.055 | 2.101 | 117,750 | 171,258 | 1.454× |

Values are medians of seven repetitions. Relative MAD was below 0.12% for
both kernels at both scales. The current kernel used C11 and the original
used GNU89; both used
`-O2 -fno-fast-math -ffp-contract=off -fno-strict-aliasing`.
[throughput-gcc-13.3.0.json](throughput-gcc-13.3.0.json) retains the measurements.

Across CPUs 0 and 12, the same binaries measured 1.45–1.67× speedup.
CPU 12 yielded 1.664× at 1× and 1.670× at 10×. Both kernels run faster on
CPU 0, but the original gains more, so the relative speedup is lower.

## Psychrometric opt-out

The 1.2.0 opt-out and private-helper changes compare against pre-change
`main` (`027d41b`), using CPU 0 and the same corpus, date, and repetitions as above.
Numerical tests verify default and retained opt-out outputs are unchanged.

| Compiler | Scale | Default/main | Opt-out/main |
|---|---:|---:|---:|
| GCC 13.3.0 | 1× | 1.043× | 1.642× |
| GCC 13.3.0 | 10× | 1.042× | 1.643× |
| Clang 18.1.3 | 1× | 1.003× | 1.577× |
| Clang 18.1.3 | 10× | 1.003× | 1.574× |

Relative median absolute deviations were below 0.1% for every mode and scale.

## Reproduce

From the repository root on Linux, build all three kernels with the same
compiler, then run both comparisons on CPU 0:

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure

mkdir -p build/baseline-src
git archive 027d41b | tar -x -C build/baseline-src
cmake -S build/baseline-src -B build/baseline -DCMAKE_BUILD_TYPE=Release
cmake --build build/baseline --target lwbgt_benchmark --parallel

# Original versus current
taskset -c 0 python3 benchmarks/compare.py \
  build/lwbgt_reference_benchmark build/lwbgt_benchmark build/cases.csv \
  build/throughput.json

# Pre-change main versus current, including opt-out
taskset -c 0 python3 benchmarks/compare.py \
  build/baseline/lwbgt_benchmark build/lwbgt_benchmark build/cases.csv \
  build/optional-psychrometric.json --skip-psychrometric
```

Both commands use seven repetitions at 1× and 10× and save raw timings and
hashes under `build/`. Change `taskset -c 0` to select another available CPU.
Use `--repetitions N` (minimum three) or `--compiler-flags='...'` to adjust
repetitions or record build flags.

Results depend on hardware, compiler, and workload. See
[the numerical baseline](../tests/BASELINE.md) for synthetic input coverage
and comparison limits.
