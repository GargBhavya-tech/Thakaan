#!/usr/bin/env python3
"""Report compiler differences; reject status and output-classification changes."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import struct
import subprocess
from pathlib import Path


def classification(value: float) -> str:
    if math.isnan(value):
        return "nan"
    if math.isinf(value):
        return "+inf" if value > 0 else "-inf"
    return "failure-sentinel" if value == -9999.0 else "finite"


def float_value(bits: str) -> tuple[float, int]:
    if len(bits) != 8:
        raise ValueError(f"expected eight hexadecimal digits, got {bits!r}")
    raw = int(bits, 16)
    value = struct.unpack(">f", raw.to_bytes(4, "big"))[0]
    # Monotonic ordering of IEEE binary32 values; both signed zeros map together.
    order = 0x80000000 - (raw & 0x7fffffff) if raw & 0x80000000 else 0x80000000 + raw
    return value, order


def compare_outputs(reference: bytes, candidate: bytes,
                    cohorts: dict[str, str] | None = None) -> dict:
    left = csv.DictReader(io.StringIO(reference.decode("ascii")))
    right = csv.DictReader(io.StringIO(candidate.decode("ascii")))
    if not left.fieldnames or left.fieldnames != right.fieldnames or "case_id" not in left.fieldnames:
        raise ValueError("probe headers differ or lack case_id")
    expected, actual = list(left), list(right)
    if not expected or len(expected) != len(actual):
        raise ValueError("probe row counts differ or are empty")
    identifiers = [row["case_id"] for row in expected]
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("duplicate reference case identifiers")
    if cohorts is not None and set(identifiers) != set(cohorts):
        raise ValueError("probe cases differ from the input corpus")

    columns = [name for name in left.fieldnames if name not in {"case_id", "status"}]
    fields = {name: {"different_bits": 0, "classification_mismatches": 0,
                     "max_absolute_difference": 0.0, "max_ulp_difference": 0}
              for name in columns}
    report = {
        "cases": len(expected), "differing_rows": 0, "status_mismatches": 0,
        "classification_mismatch_rows": 0, "fields": fields, "cohorts": {},
        "semantic_mismatch_examples": [],
        "reference_sha256": hashlib.sha256(reference).hexdigest(),
        "candidate_sha256": hashlib.sha256(candidate).hexdigest(),
    }
    for a, b in zip(expected, actual):
        if a["case_id"] != b["case_id"]:
            raise ValueError("probe case order differs")
        if None in a or None in b or any(value is None for value in [*a.values(), *b.values()]):
            raise ValueError("malformed probe row")
        status_changed = "status" in a and int(a["status"]) != int(b["status"])
        classification_changed = False
        for name in columns:
            x, ix = float_value(a[name])
            y, iy = float_value(b[name])
            field = fields[name]
            field["different_bits"] += a[name] != b[name]
            if classification(x) != classification(y):
                field["classification_mismatches"] += 1
                classification_changed = True
            elif classification(x) == "finite":
                field["max_absolute_difference"] = max(field["max_absolute_difference"], abs(x - y))
                field["max_ulp_difference"] = max(field["max_ulp_difference"], abs(ix - iy))
        different = a != b
        report["differing_rows"] += different
        report["status_mismatches"] += status_changed
        report["classification_mismatch_rows"] += classification_changed
        cohort = (cohorts or {}).get(a["case_id"], "all")
        counts = report["cohorts"].setdefault(cohort, {
            "cases": 0, "differing_rows": 0, "status_mismatches": 0,
            "classification_mismatch_rows": 0,
        })
        counts["cases"] += 1
        counts["differing_rows"] += different
        counts["status_mismatches"] += status_changed
        counts["classification_mismatch_rows"] += classification_changed
        if (status_changed or classification_changed) and len(report["semantic_mismatch_examples"]) < 20:
            report["semantic_mismatch_examples"].append({
                "cohort": cohort, "reference": a, "candidate": b,
            })
    report["status_and_classification_match"] = not (
        report["status_mismatches"] or report["classification_mismatch_rows"]
    )
    report["finite_values_match"] = not any(
        field["max_ulp_difference"] for field in fields.values()
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference")
    parser.add_argument("candidate")
    parser.add_argument("output", type=Path)
    parser.add_argument("--cases", type=Path)
    parser.add_argument("--exact-finite", action="store_true",
                        help="also reject finite numerical differences (signed zeros compare equal)")
    args = parser.parse_args()
    arguments = [str(args.cases)] if args.cases else []
    reference = subprocess.check_output([args.reference, *arguments])
    candidate = subprocess.check_output([args.candidate, *arguments])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix(".reference.csv").write_bytes(reference)
    args.output.with_suffix(".candidate.csv").write_bytes(candidate)
    cohorts = None
    if args.cases:
        with args.cases.open(encoding="ascii", newline="") as stream:
            records = list(csv.DictReader(stream))
        if records and "case_id" in records[0]:
            cohorts = {row["case_id"]: row["cohort"] for row in records}
        elif len(reference.splitlines()) - 1 != len(records):
            raise ValueError("probe row count differs from the input corpus")
    report = compare_outputs(reference, candidate, cohorts)
    report.update(reference=args.reference, candidate=args.candidate)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True, allow_nan=False))
    passed = report["status_and_classification_match"] and (
        not args.exact_finite or report["finite_values_match"]
    )
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
