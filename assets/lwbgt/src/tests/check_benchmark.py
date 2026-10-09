#!/usr/bin/env python3
"""Check throughput accounting, including failing rows and rejected scales."""

from __future__ import annotations

import csv
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from generate_cases import HEADER


def main() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        cases = Path(temporary, "cases.csv")
        with cases.open("w", encoding="ascii", newline="") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(HEADER)
            # A successful 2 m solve and a known pressure-pole failure. Cohort
            # labels must never determine whether a row is timed.
            writer.writerow(("success", "arbitrary-success", 2024, 3, 20, 12, 0, 0, 0,
                             0, 0, 700, 1013, 25, 50, 2, 2, 0, 0))
            writer.writerow(("failure", "arbitrary-failure", 2024, 3, 20, 12, 0, 0, 0,
                             0, 0, 1000, 225, 60, 100, 0.099, 2, 0, 0))
        modes = [(executable, []) for executable in sys.argv[1:]]
        modes.append((sys.argv[2], ["--skip-psychrometric"]))
        for executable, options in modes:
            checksum = None
            for scale in (1, 10):
                result = subprocess.run([executable, str(cases), str(scale), *options],
                                        check=True, stdout=subprocess.PIPE, text=True)
                sample = json.loads(result.stdout)
                assert sample["cases"] == 2 and sample["scale"] == scale
                assert sample["calls"] == 2 * scale
                assert sample["successes"] == scale and sample["failures"] == scale
                assert sample["elapsed_seconds"] > 0 and sample["rows_per_second"] > 0
                if checksum is None:
                    checksum = sample["checksum"]
                assert sample["checksum"] == checksum * scale
            for scale in ("0", "-1", "x", "999999999999999999999"):
                result = subprocess.run([executable, str(cases), scale, *options],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                assert result.returncode != 0, f"accepted invalid scale {scale}"
        rejected = subprocess.run([sys.argv[1], str(cases), "1", "--skip-psychrometric"],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        assert rejected.returncode == 2, "original kernel accepted an unsupported opt-out"
        output = Path(temporary, "report.json")
        subprocess.run([sys.executable, str(Path(__file__).parents[1] / "benchmarks/compare.py"),
                        *sys.argv[1:], str(cases), str(output), "--repetitions", "3",
                        "--skip-psychrometric"], check=True)
        report = json.loads(output.read_text())
        assert report["corpus_cases"] == 2
        assert report["repetitions"] == 3 and set(report["scales"]) == {"1", "10"}
        for scale, result in report["scales"].items():
            assert result["calls"] == 2 * int(scale)
            assert result["failures"] == int(scale)
            for name in ("reference", "candidate", "skip"):
                assert len(result[name]["elapsed_seconds"]) == 3
                assert result[name]["median_elapsed_seconds"] > 0
            assert result["skip_speedup"] > 0
        print("benchmark accounting passed for all modes and both scales")


if __name__ == "__main__":
    main()
