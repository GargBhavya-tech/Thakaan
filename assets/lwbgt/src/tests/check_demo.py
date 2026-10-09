#!/usr/bin/env python3
"""Compile and exercise the optional Liljegren demonstration driver."""

from __future__ import annotations

import math
import subprocess
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: check_demo.py DEMO_EXECUTABLE")
    sample = (
        "header\n"
        "0 0 2024 0 0 2 0\n"
        "day time u30 u10 u2 solar pressure rh air dt30 dt10\n"
        "80 1200 0 2 2 800 1013 50 25 0 0\n"
    )
    result = subprocess.run(
        [sys.argv[1]], input=sample, text=True, capture_output=True, check=True
    )
    fields = [float(value) for value in result.stdout.split()]
    if len(fields) != 10 or not all(math.isfinite(value) for value in fields):
        raise SystemExit("demo did not produce one finite output row")
    if any(value == -9999.0 for value in fields):
        raise SystemExit("demo calculation failed")


if __name__ == "__main__":
    main()
