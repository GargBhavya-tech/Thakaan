#!/usr/bin/env python3
"""Run byte-exact differential tests against the retained original."""

from __future__ import annotations

import csv
import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

SUCCESS_COHORTS = {
    "year-sweep", "calendar-boundary", "solar-height2", "wind-stability",
    "thermophysical", "float-conversion",
    "wind-threshold", "radiation-threshold", "height-sign",
    "solar-global", "horizon-minute", "solar-clipping", "time-calendar",
}


def run(command: list[str]) -> bytes:
    return subprocess.run(command, check=True, stdout=subprocess.PIPE).stdout


def exact(reference: str, candidate: str, cases: str | None = None) -> None:
    arguments = [cases] if cases is not None else []
    expected = run([reference, *arguments])
    actual = run([candidate, *arguments])
    if actual != expected:
        expected_lines = expected.splitlines()
        actual_lines = actual.splitlines()
        for ordinal, (left, right) in enumerate(
            zip(expected_lines, actual_lines, strict=False), start=1
        ):
            if left != right:
                raise SystemExit(
                    f"exact mismatch at output line {ordinal}:\n"
                    f"reference: {left.decode()}\ncandidate: {right.decode()}"
                )
        raise SystemExit("exact output lengths differ")
    print(json.dumps({
        "cases": max(0, len(expected.splitlines()) - 1),
        "sha256": hashlib.sha256(expected).hexdigest(),
        "status": "bit-identical",
    }, sort_keys=True))


def compatibility(reference: str, candidate: str, cases: str) -> None:
    with Path(cases).open(encoding="ascii", newline="") as stream:
        records = list(csv.DictReader(stream))
    expected = run([reference, cases]).decode("ascii").splitlines()
    actual = run([candidate, cases]).decode("ascii").splitlines()
    if len(expected) != len(records) + 1 or len(actual) != len(expected):
        raise SystemExit("compatibility output lengths differ")
    if expected[0] != actual[0]:
        raise SystemExit("compatibility output headers differ")

    corrected = 0
    cohorts: dict[str, dict[str, int]] = {}
    for record, reference_line, candidate_line in zip(
        records, expected[1:], actual[1:], strict=True
    ):
        reference_fields = reference_line.split(",")
        candidate_fields = candidate_line.split(",")
        if len(reference_fields) != 8 or len(candidate_fields) != 8:
            raise SystemExit(f"invalid probe output for {record['case_id']}")
        if reference_fields[0] != record["case_id"]:
            raise SystemExit(f"incorrect oracle case id for {record['case_id']}")
        if reference_fields[1] not in {"0", "-1"}:
            raise SystemExit(f"unexpected oracle status for {record['case_id']}")
        cohort = cohorts.setdefault(record["cohort"], {"success": 0, "failure": 0})
        cohort["success" if reference_fields[1] == "0" else "failure"] += 1
        if record["cohort"] in SUCCESS_COHORTS and reference_fields[1] != "0":
            raise SystemExit(f"oracle failed for valid case {record['case_id']}")
        height = struct.unpack("=f", struct.pack("=f", float(record["wind_height"])))[0]
        if height == 2.0:
            wind_bits = struct.unpack("=I", struct.pack("=f", float(record["wind"])))[0]
            if candidate_fields[2] != f"{wind_bits:08x}":
                raise SystemExit(f"incorrect scalar 2 m wind for {record['case_id']}")
            reference_fields[2] = candidate_fields[2]
            corrected += 1
        if reference_fields != candidate_fields:
            raise SystemExit(
                f"compatibility mismatch for {record['case_id']}:\n"
                f"reference: {reference_line}\ncandidate: {candidate_line}"
            )
    for name in ("seeded-nominal", "seeded-stress", "thermophysical-extreme"):
        if name in cohorts and cohorts[name]["success"] == 0:
            raise SystemExit(f"oracle cohort {name} has no successful solves")
    print(json.dumps({
        "cases": len(records),
        "corrected_2m_wind_cases": corrected,
        "cohorts": cohorts,
        "reference_sha256": hashlib.sha256(("\n".join(expected) + "\n").encode("ascii")).hexdigest(),
        "candidate_sha256": hashlib.sha256(("\n".join(actual) + "\n").encode("ascii")).hexdigest(),
        "status": "bit-identical except corrected scalar 2 m wind",
    }, sort_keys=True))


def main() -> None:
    if len(sys.argv) in (4, 5) and sys.argv[1] == "exact":
        exact(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) == 5 else None)
        return
    if len(sys.argv) == 5 and sys.argv[1] == "compat":
        compatibility(sys.argv[2], sys.argv[3], sys.argv[4])
        return
    raise SystemExit(
        "usage: compare.py exact REF CANDIDATE [CASES] | "
        "compare.py compat REF CANDIDATE CASES"
    )


if __name__ == "__main__":
    main()
