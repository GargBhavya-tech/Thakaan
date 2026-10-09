#!/usr/bin/env python3
"""Compare scalar kernel throughput at 1x and 10x corpus sizes."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import platform
import statistics
import subprocess
from datetime import datetime, timezone
from pathlib import Path

SCALES = (1, 10)


def measured(executable: str, cases: Path, scale: int, count: int,
             skip_psychrometric: bool = False) -> dict:
    command = [executable, str(cases), str(scale)]
    if skip_psychrometric:
        command.append("--skip-psychrometric")
    result = subprocess.run(command, check=True,
                            stdout=subprocess.PIPE, text=True)
    sample = json.loads(result.stdout)
    calls = count * scale
    if (sample["cases"] != count or sample["scale"] != scale or
            sample["calls"] != calls or
            sample["successes"] + sample["failures"] != calls):
        raise RuntimeError("benchmark call counts differ from the requested workload")
    elapsed = sample["elapsed_seconds"]
    rate = sample["rows_per_second"]
    if not (math.isfinite(elapsed) and elapsed > 0 and math.isfinite(rate) and rate > 0):
        raise RuntimeError("benchmark timing must be finite and positive")
    if not math.isclose(rate, calls / elapsed, rel_tol=1e-12):
        raise RuntimeError("benchmark rate differs from calls / elapsed time")
    return sample


def summarize(samples: list[dict]) -> dict:
    for field in ("successes", "failures", "checksum", "cpu", "compiler"):
        if any(sample[field] != samples[0][field] for sample in samples):
            raise RuntimeError(f"benchmark {field} changed between repetitions")
    rates = [sample["rows_per_second"] for sample in samples]
    median = statistics.median(rates)
    return {
        "compiler": samples[0]["compiler"],
        "cpu": samples[0]["cpu"],
        "checksum": samples[0]["checksum"],
        "elapsed_seconds": [sample["elapsed_seconds"] for sample in samples],
        "median_elapsed_seconds": statistics.median(sample["elapsed_seconds"] for sample in samples),
        "median_rows_per_second": median,
        "relative_mad": statistics.median(abs(rate - median) for rate in rates) / median,
    }


def cpu_model() -> str:
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.exists():
        for line in cpuinfo.read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    return platform.processor()


def benchmark(reference: str, candidate: str, cases: Path, output: Path,
              repetitions: int, compiler_flags: str | None = None,
              skip_psychrometric: bool = False) -> dict:
    if repetitions < 3:
        raise ValueError("at least three repetitions are required")
    with cases.open(encoding="ascii", newline="") as stream:
        count = sum(1 for _ in csv.DictReader(stream))
    if not count:
        raise ValueError("corpus is empty")
    executables = {"reference": reference, "candidate": candidate}
    if skip_psychrometric:
        executables["skip"] = candidate
    report = {
        "date": datetime.now(timezone.utc).date().isoformat(),
        "platform": platform.platform(),
        "cpu_model": cpu_model(),
        "compiler_flags": compiler_flags,
        "corpus_cases": count,
        "corpus_sha256": hashlib.sha256(cases.read_bytes()).hexdigest(),
        "executable_sha256": {name: hashlib.sha256(Path(exe).read_bytes()).hexdigest()
                              for name, exe in executables.items()},
        "repetitions": repetitions,
        "method": "single-thread scalar calls; one full-corpus warm-up per execution; "
                  "input loading and warm-up excluded; rotating mode order; "
                  "all rows included; CPU affinity recorded per kernel",
        "scales": {},
    }
    base_counts = None
    selected_cpu = None
    for scale in SCALES:
        samples: dict[str, list[dict]] = {name: [] for name in executables}
        for repetition in range(repetitions):
            order = list(executables)
            rotation = repetition % len(order)
            order = order[rotation:] + order[:rotation]
            for name in order:
                sample = measured(executables[name], cases, scale, count, name == "skip")
                samples[name].append(sample)
                print(f"{scale}x {name} {repetition + 1}/{repetitions}: "
                      f"{sample['elapsed_seconds']:.3f} s, "
                      f"{sample['rows_per_second']:.0f} rows/s", flush=True)
        summaries = {name: summarize(values) for name, values in samples.items()}
        reference_sample = samples["reference"][0]
        counts = (reference_sample["successes"] // scale, reference_sample["failures"] // scale)
        if base_counts is None:
            base_counts = counts
            selected_cpu = reference_sample["cpu"]
        for values in samples.values():
            for sample in values:
                if ((sample["successes"], sample["failures"]) !=
                        (base_counts[0] * scale, base_counts[1] * scale)):
                    raise RuntimeError("kernel convergence counts differ across workloads")
                if sample["cpu"] != selected_cpu:
                    raise RuntimeError("kernel CPU affinity differs across workloads")
        report["scales"][str(scale)] = {
            "calls": count * scale,
            "successes": base_counts[0] * scale,
            "failures": base_counts[1] * scale,
            **summaries,
            "speedup": summaries["candidate"]["median_rows_per_second"] /
                       summaries["reference"]["median_rows_per_second"],
        }
        if skip_psychrometric:
            report["scales"][str(scale)]["skip_speedup"] = (
                summaries["skip"]["median_rows_per_second"] /
                summaries["reference"]["median_rows_per_second"]
            )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference")
    parser.add_argument("candidate")
    parser.add_argument("cases", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--repetitions", type=int, default=7)
    parser.add_argument("--compiler-flags", help="record the kernel build flags")
    parser.add_argument("--skip-psychrometric", action="store_true",
                        help="also measure the candidate with psychrometric wet-bulb omitted")
    args = parser.parse_args()
    if args.repetitions < 3:
        parser.error("--repetitions must be at least three")
    benchmark(args.reference, args.candidate, args.cases, args.output,
              args.repetitions, args.compiler_flags, args.skip_psychrometric)


if __name__ == "__main__":
    main()
