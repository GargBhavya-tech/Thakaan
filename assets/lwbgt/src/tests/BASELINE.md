# Numerical baseline

`tests/generate_cases.py` produces one **35,976-row corpus** for numerical
comparison and throughput measurement. Every status and output bit matches the
retained original in matched Linux x86_64 builds with GCC 13.3.0 and Clang
18.1.3, except the corrected scalar wind output at heights that round to 2 m.

[baseline.json](baseline.json) records the input/output hashes, compiler flags,
seeds, and convergence counts. The pinned oracle blob is
`7bc6e6ddd76a538d6454b27e9b252667846e6c9b`. Both compilers produced the same
hashes using `-O2 -fno-fast-math -ffp-contract=off -fno-strict-aliasing`.
Other compiler, architecture, and math-library combinations may differ.

## Corpus

| Cohort | Rows | Failures | Coverage |
|---|---:|---:|---|
| Known answers | 2 | 0 | Fixed regression endpoints |
| Fixed weather | 2 | 0 | London examples with unrecorded source provenance |
| Interactions | 432 | 0 | Day/night, radiation, RH, wind, height, urban/rural, averaging |
| Pressure boundaries | 6 | 6 | Vapor-pressure/ambient-pressure poles and low wind |
| Domain edges | 4 | 2 | Cold/hot, dry/saturated, polar, low-pressure inputs |
| Finite invalid weather | 6 | 3 | Out-of-range RH, radiation, wind, pressure, height |
| Year sweep | 100 | 0 | Every supported year, 1950–2049 |
| Calendar boundaries | 52 | 0 | Leap days, year boundaries, calendar and ordinal dates |
| Solar geometry at 2 m | 96 | 0 | Seasons, hemispheres, horizons, zero/nonzero radiation |
| Wind stability | 96 | 0 | Threshold neighborhoods, day/night, inversion, urban/rural |
| Thermophysical | 48 | 0 | Temperature, pressure, humidity, radiative branches |
| Float conversion | 8 | 0 | Double inputs near binary32 rounding boundaries |
| Wind thresholds | 2,736 | 0 | Adjacent binary32 values at 0.13, 2, 2.5, 3, 5, 6 m/s |
| Radiation thresholds | 150 | 0 | Neighbors of 175, 675, 925 W/m² before solar adjustment |
| Height/inversion sign | 128 | 0 | 2 m rounding, signed zero, subnormal differences |
| Global solar geometry | 9,360 | 0 | Poles, date line, polar circles, tropics, seasons, averaging |
| Horizon minutes | 2,178 | 0 | Minute sweeps across morning/evening windows and three longitudes |
| Solar clipping | 300 | 0 | Near-zero radiation, irradiance cap, sensor overshoot |
| Calendar/time | 6,912 | 0 | Month starts/ends, UTC rollover, interval centering, both date forms |
| Thermophysical extremes | 3,360 | 1,025 | Air -60–60 °C, pressure 200–1,100 hPa, RH 0.001–100%, wind 0–40 m/s |
| Seeded nominal | 5,000 | 5 | Air -10–45 °C, pressure 700–1,100 hPa, RH 10–100%, wind 0–15 m/s, height 1–30 m, solar 0–1,200 W/m² |
| Seeded stress | 5,000 | 2,035 | Air -80–65 °C, pressure 200–1,100 hPa, RH 0.001–100%, wind 0–40 m/s, height 0.1–100 m, solar 0–2,000 W/m² |

There are **32,900 successful solves and 3,076 failures**. Failure status means
at least one temperature solve failed; other outputs can still be finite.
Success cohorts in `compare.py` must converge. Extreme and seeded cohorts
retain failure behavior; five nominal-range cases fail, so nominal ranges alone
do not guarantee convergence.

Seeded nominal/stress cohorts use independent uniform samples with seeds
**20261001/20261002**. They also vary all supported years, valid Gregorian
dates, global coordinates, GMT offsets -12 to +14 h, averaging intervals
0/15/30/60/180/1440 min, inversion differences -5 to 5 °C, and urban/rural.
The generator advances dates with a negative centered fractional day by one
day to stay inside the oracle's solar guard. The original date arithmetic is
preserved; calendar/ordinal pairs are not assumed equivalent. Radiation is
sampled independently of solar geometry. These are regression fixtures and
synthetic grids/samples, not a verified observed-weather dataset.

## Additional checks

- **996 internal diagnostics** compare stability classes, adjacent longitude
  floats straddling cosine thresholds 0 and `CZA_MIN`, and radiation clipping.
  Calls respect the original K&R argument promotions.
- **1,682 saturation-pressure cases** cover water/ice, 173.15–373.15 K in
  0.25 K steps, adjacent floats and rounding midpoints, signed zero, NaN, and
  infinities. Nonphysical inputs check inherited behavior, not physical accuracy.
- **64 invalid/non-finite weather rows** check current scalar/batch/Python
  consistency. They have no oracle equivalence claim: NaN solar can fail the
  original psychrometric solve while the derivative returns finite `Tpsy`.
- **18 rejected solar cases** require status -1 and five -9999 outputs through
  current scalar/batch calls. The original consumes unwritten outputs on solar
  rejection, so these cases are excluded from oracle comparison.

The full corpus runs through static and Python-runtime probes, scalar/batch
checks in chunks of up to 1,024 rows, and installed-wheel tests. Numerical
comparison runs in **CI**; full throughput measurement is a
separate local run.

## Reproduce

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```

Increase seeded coverage with:

```sh
python3 tests/generate_cases.py build/large-cases.csv --samples 100000
python3 tests/compare.py compat build/lwbgt_reference_probe build/lwbgt_probe \
  build/large-cases.csv
```

Keep discovered failures as regressions and refresh baseline hashes and
[throughput results](../benchmarks/README.md) when changing the default corpus.
Sampling does not prove all-input equivalence or validate scientific accuracy
against independent observations.
