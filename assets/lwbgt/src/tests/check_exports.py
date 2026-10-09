#!/usr/bin/env python3
"""Verify that the shared library exposes only the supported C ABI."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

EXPECTED = {"calc_wbgt", "esat", "lwbgt_calc_batch_v1", "lwbgt_calc_batch_ex_v1"}


def run(*command: str) -> str:
    return subprocess.run(
        command, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    ).stdout


def exported(system: str, library: str, nm: str, objdump: str) -> set[str]:
    if system == "Windows":
        if Path(objdump).stem.lower() == "dumpbin":
            output = run(objdump, "/nologo", "/exports", library)
            return set(re.findall(
                r"^\s*\d+\s+[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+(\w+)\s*$",
                output, re.MULTILINE,
            ))
        output = run(objdump, "-p", library)
        table = output.split("[Ordinal/Name Pointer] Table", 1)
        if len(table) != 2:
            raise RuntimeError("objdump did not contain a PE export table")
        names: set[str] = set()
        for line in table[1].splitlines():
            match = re.match(r"\s*\[\s*\d+\]\s+(\S+)\s*$", line)
            if match:
                names.add(match.group(1))
            elif names and line.strip() and not line.startswith("\t"):
                break
        return names

    arguments = [nm, "-gU"] if system == "Darwin" else [nm, "-D", "--defined-only"]
    names = {line.split()[-1] for line in run(*arguments, library).splitlines() if line.split()}
    if system == "Darwin":
        names = {name.removeprefix("_") for name in names}
    return {name.split("@", 1)[0] for name in names}


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit("usage: check_exports.py SYSTEM LIBRARY NM OBJDUMP")
    actual = exported(*sys.argv[1:])
    if actual != EXPECTED:
        raise SystemExit(f"export mismatch: expected {sorted(EXPECTED)}, got {sorted(actual)}")
    print("shared exports:", ", ".join(sorted(actual)))


if __name__ == "__main__":
    main()
