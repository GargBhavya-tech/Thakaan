#!/usr/bin/env python3
"""Generate the shared oracle and throughput corpus, plus API edge cases."""

from __future__ import annotations

import argparse
import csv
import itertools
import random
import struct
from calendar import isleap, monthrange
from datetime import date
from pathlib import Path

HEADER = (
    "case_id", "cohort", "year", "month", "day", "hour", "minute", "gmt",
    "avg", "lat", "lon", "solar", "pressure", "air", "rh", "wind",
    "wind_height", "vertical_delta", "urban",
)

SEED = 20261001
SAMPLES = 10000


def float_neighbors(value: float) -> tuple[float, float, float]:
    """Adjacent binary32 values around a positive, finite threshold."""
    bits = struct.unpack("=I", struct.pack("=f", value))[0]
    return tuple(struct.unpack("=f", struct.pack("=I", bits + step))[0]
                 for step in (-1, 0, 1))


def write_csv(path: Path, header: tuple[str, ...], rows: list[tuple]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="ascii", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def wbgt_cases(samples: int, seed: int) -> list[tuple]:
    rows: list[tuple] = []
    defaults = dict(zip(HEADER[2:], (
        2024, 3, 20, 12, 0, 0, 0, 0.0, 0.0, 700.0, 1013.0,
        25.0, 50.0, 2.0, 10.0, 0.0, 0,
    )))

    def add(cohort: str, *inputs: object, **changes: object) -> None:
        values = (dict(zip(HEADER[2:], inputs, strict=True))
                  if inputs else defaults | changes)
        centered_day = values["day"] + (values["hour"] - values["gmt"] +
                                       (values["minute"] - 0.5 * values["avg"]) / 60) / 24
        if centered_day < 0:
            # The original consumes unwritten solar outputs outside its date
            # guard. Test rejected dates separately against the current API.
            values["day"] += 1
        rows.append((f"case-{len(rows) + 1:06d}", cohort,
                     *(values[key] for key in HEADER[2:])))

    # Fixed known-answer endpoints.
    add("known-answer", 2024, 3, 21, 7, 0, 0, 0, 14.515380, -91.977540,
        0.0, 1010.0, 42.543334, 31.5, 0.821752, 2.0, 0.0, 0)
    add("known-answer", 2024, 9, 22, 13, 0, 0, 0, 11.359619, 11.997070,
        933.496093, 1010.0, 20.215454, 52.0, 3.763891, 2.0, 0.0, 0)

    # Fixed London weather examples; source provenance is not recorded.
    add("fixed-weather", 2023, 7, 18, 15, 30, 0, 60, 51.5074, -0.1278,
        612.5, 1004.2, 28.4, 55.0, 4.7, 10.0, -0.8, 0)
    add("fixed-weather", 2023, 1, 18, 3, 30, 0, 60, 51.5074, -0.1278,
        0.0, 1018.6, 2.3, 91.0, 3.2, 10.0, 0.5, 0)

    # Cartesian coverage of day/night, radiation, humidity, wind, height,
    # wind conversion, and averaging policy.
    for hour, solar, rh, wind, height, urban, avg in itertools.product(
        (0, 12), (0.0, 80.0, 950.0), (5.0, 50.0, 99.0),
        (0.0, 0.13, 8.0), (2.0, 10.0), (0, 1), (0, 60),
    ):
        add("interactions", 2024, 6, 21, hour, 30, 0, avg, 35.0, -80.0,
            solar, 1010.0, 35.0, rh, wind, height, 1.5, urban)

    # Solver/domain boundary coverage, including known pressure-pole cases.
    for pressure, wind in ((225.0, 0.099), (225.0, 0.101), (300.0, 0.129),
                           (300.0, 0.131), (500.0, 0.099), (700.0, 0.131)):
        add("pressure-boundary", 2024, 3, 20, 12, 0, 0, 0, 0.0, 0.0,
            1000.0, pressure, 60.0, 100.0, wind, 2.0, 0.0, 0)
    for air, rh, solar, wind in (
        (-50.0, 0.01, 0.0, 0.0), (60.0, 99.99, 1100.0, 0.129),
        (0.0, 100.0, 0.0, 0.131), (45.0, 1.0, 1.0, 40.0),
    ):
        add("domain-edge", 1950, 1, 1, 0, 0, -12, 180, 89.9, -179.9,
            solar, 200.0, air, rh, wind, 10.0, -20.0, 1)

    # Finite but physically invalid inputs remain oracle-defined regression cases.
    for pressure, rh, solar, wind, height in (
        (1010.0, -1.0, 500.0, 2.0, 2.0),
        (1010.0, 101.0, 500.0, 2.0, 2.0),
        (1010.0, 50.0, -10.0, 2.0, 2.0),
        (1010.0, 50.0, 500.0, -1.0, 2.0),
        (0.0, 50.0, 500.0, 2.0, 2.0),
        (1010.0, 50.0, 500.0, 2.0, 0.0),
    ):
        add("finite-invalid", 2049, 12, 31, 23, 59, 12, 30, -89.9, 179.9,
            solar, pressure, 25.0, rh, wind, height, 20.0, 0)

    # Every supported year, including the original leap-year arithmetic.
    for year in range(1950, 2050):
        month, day = ((3, 20), (9, 22))[year % 2]
        hour = (0, 6, 12, 18)[year % 4]
        add("year-sweep", year, month, day, hour, 0, 0, 0,
            (-45.0, 0.0, 45.0)[year % 3], 0.0,
            650.0 if hour == 12 else 0.0, 1013.0,
            15.0 + 5.0 * (year % 5), 30.0 + 20.0 * (year % 3),
            1.0 + (year % 4), (2.0, 10.0)[year % 2],
            (-1.0, 0.0, 1.0)[year % 3], year % 2)

    # Month/day and day-of-year forms around leap days and year boundaries.
    for year in (1950, 1952, 1999, 2000, 2001, 2004, 2048, 2049):
        dates = [(1, 1), (2, 28), (3, 1), (12, 31)]
        if isleap(year):
            dates.insert(2, (2, 29))
        for month, day in dates:
            add("calendar-boundary", year, month, day, 12, 0, 0, 0,
                0.0, 0.0, 600.0, 1013.0, 25.0, 50.0, 2.0, 2.0, 0.0, 0)
            if (month, day) in ((3, 1), (12, 31)):
                ordinal = (60 + isleap(year)) if month == 3 else (365 + isleap(year))
                add("calendar-boundary", year, 0, ordinal, 12, 0, 0, 0,
                    0.0, 0.0, 600.0, 1013.0, 25.0, 50.0, 2.0, 2.0, 0.0, 0)

    # Zero/nonzero radiation through different seasons, latitudes, and horizons.
    for lat, (month, day), hour, solar in itertools.product(
        (-60.0, 0.0, 60.0), ((3, 20), (6, 21), (9, 22), (12, 21)),
        (5, 7, 17, 19), (0.0, 700.0),
    ):
        add("solar-height2", 2024, month, day, hour, 0, 0, 0,
            lat, 0.0, solar, 1013.0, 25.0, 50.0, 2.0, 2.0, 0.0, 0)

    # All wind-stability sides near the 0.13, 2.0, and 2.5 m/s thresholds.
    for wind, delta, urban, hour in itertools.product(
        (0.129, 0.13, 0.131, 1.9999, 2.0, 2.4999, 2.5, 8.0),
        (-1.0, 0.0, 1.0), (0, 1), (0, 12),
    ):
        add("wind-stability", 2024, 3, 20, hour, 0, 0, 0,
            0.0, 0.0, 700.0 if hour == 12 else 0.0,
            1013.0, 25.0, 50.0, wind, 10.0, delta, urban)

    # Vary temperatures, pressures, humidity, and both radiative branches.
    for air, pressure, rh, solar in itertools.product(
        (-5.0, 15.0, 30.0, 40.0), (700.0, 900.0, 1013.0),
        (20.0, 80.0), (0.0, 800.0),
    ):
        add("thermophysical", 2024, 7, 1, 12, 0, 0, 60,
            25.0, 0.0, solar, pressure, air, rh,
            0.5 if air < 20.0 else 3.7, 2.0 if solar == 0.0 else 10.0,
            -0.5, 1)

    # Scalar double inputs deliberately close to single-precision boundaries.
    for index, wind in enumerate((
        0.129999995, 0.130000009, 1.99999994, 2.00000006,
        2.49999994, 2.50000006, 3.141592653589793, 8.765432198765,
    )):
        add("float-conversion", 2024, 4, 15, 10 + index % 4, 17, 0, 30,
            23.4567890123, -45.6789012345,
            0.0 if index % 2 else 653.123456789,
            1013.567890123, 25.123456789 + index / 10,
            67.987654321, wind, 2.0 if index % 2 else 10.0,
            -0.123456789, index % 2)

    # Exercise every wind-stability threshold on both sides after ABI rounding.
    winds = (0.0, *float_neighbors(0.13), *float_neighbors(2.0),
             *float_neighbors(2.5), *float_neighbors(3.0),
             *float_neighbors(5.0), *float_neighbors(6.0))
    for wind, hour, solar, height, urban, delta in itertools.product(
        winds, (0, 12), (0.0, 100.0, 500.0, 1000.0),
        (2.0, 10.0, 100.0), (0, 1), (-1.0, 0.0, 1.0),
    ):
        add("wind-threshold", wind=wind, hour=hour, solar=solar,
            wind_height=height, urban=urban, vertical_delta=delta)

    # Solar adjustment precedes stability classification, so include a wider
    # neighborhood as well as adjacent floats around the raw-input thresholds.
    solars = tuple(value for threshold in (175.0, 675.0, 925.0)
                   for value in (*float_neighbors(threshold),
                                 threshold - 0.001, threshold + 0.001))
    for solar, wind, urban in itertools.product(solars, (1.0, 2.0, 3.0, 5.0, 6.0), (0, 1)):
        add("radiation-threshold", solar=solar, wind=wind, urban=urban)

    # Values that round onto and away from the special 2 m path, and the
    # inversion sign branch, including signed zero and a subnormal magnitude.
    heights = (*float_neighbors(2.0), 1.99999999, 2.00000001, 0.1, 1.0, 30.0)
    for height, delta, hour, urban in itertools.product(
        heights, (-1e-45, -0.0, 0.0, 1e-45), (0, 12), (0, 1),
    ):
        add("height-sign", wind_height=height, vertical_delta=delta,
            wind=1.0, hour=hour, urban=urban)

    # Both hemispheres, exact coordinate endpoints, polar circles and tropics.
    for lat, lon, (month, day), hour, solar, avg in itertools.product(
        (-90.0, -89.999, -80.0, -66.56, -60.0, -23.44, 0.0,
         23.44, 60.0, 66.56, 80.0, 89.999, 90.0),
        (-180.0, -90.0, 0.0, 90.0, 180.0),
        ((3, 20), (6, 21), (9, 22), (12, 21)),
        (0, 5, 6, 7, 12, 17, 18, 19, 23), (0.0, 900.0), (0, 60),
    ):
        add("solar-global", lat=lat, lon=lon, month=month, day=day,
            hour=hour, solar=solar, avg=avg)

    # Minute-resolution sweeps across both horizons. Longitude changes move
    # these windows through other solar angles without assuming sunrise time.
    for start, offset, lon, solar in itertools.product(
        (5, 17), range(121), (-90.0, 0.0, 90.0), (0.0, 1.0, 700.0),
    ):
        minutes = start * 60 + offset
        add("horizon-minute", hour=minutes // 60, minute=minutes % 60,
            lon=lon, solar=solar, wind=1.0)

    # Radiation clipping, near-zero direct beam and sensor overshoot, across
    # different top-of-atmosphere irradiances.
    for solar, lat, hour in itertools.product(
        (0.0, 1e-6, 0.01, 1.0, 50.0, 500.0, 1000.0,
         1367.0 * 0.849, 1367.0 * 0.85, 1367.0 * 0.851, 2000.0, 5000.0),
        (-80.0, -45.0, 0.0, 45.0, 80.0), (6, 7, 12, 17, 18),
    ):
        add("solar-clipping", solar=solar, lat=lat, hour=hour)

    # Month ends, leap days, UTC rollover and interval centering, in both date
    # representations. Preserve the original date arithmetic: these pairs are
    # oracle comparisons, not an assertion of Gregorian equivalence.
    for year, month, end, hour, gmt, avg in itertools.product(
        (1950, 1952, 1999, 2000, 2001, 2004, 2048, 2049), range(1, 13),
        (False, True), (0, 23), (-12, 0, 14), (0, 60, 180),
    ):
        day = monthrange(year, month)[1] if end else 1
        ordinal = date(year, month, day).timetuple().tm_yday
        for calendar_month, calendar_day in ((month, day), (0, ordinal)):
            add("time-calendar", year=year, month=calendar_month,
                day=calendar_day, hour=hour, minute=59, gmt=gmt, avg=avg)

    # Preserve finite extreme-input behavior, including non-convergence and
    # vapor-pressure/ambient-pressure poles; success is not required here.
    for air, pressure, rh, wind, solar in itertools.product(
        (-60.0, -30.0, -0.01, 0.0, 0.01, 25.0, 45.0, 60.0),
        (200.0, 225.0, 300.0, 500.0, 700.0, 1013.0, 1100.0),
        (0.001, 1.0, 50.0, 99.0, 100.0),
        (0.0, 0.13, 1.0, 40.0), (0.0, 1.0, 1100.0),
    ):
        add("thermophysical-extreme", air=air, pressure=pressure, rh=rh,
            wind=wind, solar=solar, wind_height=2.0)

    # Use Random.random only: its sequence is reproducible across supported
    # Python versions. Keep nominal and stress cases independently seeded.
    for stress in (False, True):
        rng = random.Random(seed + int(stress))
        count = samples // 2 + (samples % 2 if stress else 0)
        for _ in range(count):
            year = 1950 + int(rng.random() * 100)
            month = 1 + int(rng.random() * 12)
            day = 1 + int(rng.random() * monthrange(year, month)[1])
            add("seeded-stress" if stress else "seeded-nominal",
                year=year, month=month, day=day,
                hour=int(rng.random() * 24), minute=int(rng.random() * 60),
                gmt=-12 + int(rng.random() * 27),
                avg=(0, 15, 30, 60, 180, 1440)[int(rng.random() * 6)],
                lat=-90.0 + rng.random() * 180.0,
                lon=-180.0 + rng.random() * 360.0,
                solar=rng.random() * (2000.0 if stress else 1200.0),
                pressure=(200.0 + rng.random() * 900.0 if stress
                          else 700.0 + rng.random() * 400.0),
                air=(-80.0 + rng.random() * 145.0 if stress
                     else -10.0 + rng.random() * 55.0),
                rh=(0.001 + rng.random() * 99.999 if stress
                    else 10.0 + rng.random() * 90.0),
                wind=rng.random() * (40.0 if stress else 15.0),
                wind_height=(0.1 + rng.random() * 99.9 if stress
                             else 1.0 + rng.random() * 29.0),
                vertical_delta=-5.0 + rng.random() * 10.0,
                urban=int(rng.random() * 2))
    return rows


def invalid_weather_cases() -> list[tuple]:
    rows: list[tuple] = []
    # The native API forwards missing/invalid weather values. Solar coordinates
    # and dates remain valid; these rows test current APIs, not oracle equality.
    invalid_weather = [
        {field: value}
        for field, value in itertools.product(
            ("solar", "pressure", "air", "rh", "wind", "wind_height", "vertical_delta"),
            (float("nan"), float("inf"), -float("inf")),
        )
    ]
    invalid_weather.extend({field: value} for field, value in (
        ("rh", 0.0), ("rh", -0.001), ("rh", 100.001),
        ("pressure", 0.0), ("pressure", -1.0), ("solar", -0.001),
        ("wind", -0.001), ("wind_height", 0.0), ("wind_height", -1.0),
        ("air", -273.15), ("air", -273.16),
    ))
    for changes, hour in itertools.product(invalid_weather, (0, 12)):
        values = dict(zip(HEADER[2:], (
            2024, 3, 20, hour, 0, 0, 0, 0.0, 0.0, 700.0, 1013.0,
            25.0, 50.0, 2.0, 10.0, 0.0, 0,
        ))) | changes
        rows.append((f"weather-{len(rows) + 1:05d}", "weather-invalid",
                     *(values[key] for key in HEADER[2:])))

    return rows


def esat_cases() -> list[tuple]:
    temperatures = [173.15 + index * 0.25 for index in range(801)]
    for threshold in (173.15, 233.15, 253.15, 273.15, 293.15, 333.15, 373.15):
        lower, center, upper = float_neighbors(threshold)
        temperatures.extend((lower, center, upper, (lower + center) / 2,
                             (center + upper) / 2))
    temperatures.extend((0.0, -0.0, float("nan"), float("inf"), -float("inf")))
    return [(temperature, phase) for temperature in temperatures for phase in (0, 1)]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--samples", type=int, default=SAMPLES,
                        help="total seeded rows, split between nominal and stress")
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--esat-output", type=Path)
    parser.add_argument("--weather-output", type=Path)
    args = parser.parse_args()
    if args.samples < 0:
        parser.error("--samples must be nonnegative")
    write_csv(args.output, HEADER, wbgt_cases(args.samples, args.seed))
    if args.weather_output:
        write_csv(args.weather_output, HEADER, invalid_weather_cases())
    if args.esat_output:
        write_csv(args.esat_output, ("temperature_k", "phase"), esat_cases())


if __name__ == "__main__":
    main()
