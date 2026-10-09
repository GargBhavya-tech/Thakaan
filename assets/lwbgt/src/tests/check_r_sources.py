#!/usr/bin/env python3
"""Require the R package to carry exact copies of the public native sources."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PAIRS = (
    ("src/wbgt.c", "r/src/wbgt.c"),
    ("include/lwbgt.h", "r/src/lwbgt.h"),
)


def main() -> None:
    mismatches = [
        f"{source} != {copy}"
        for source, copy in PAIRS
        if (ROOT / source).read_bytes() != (ROOT / copy).read_bytes()
    ]
    if mismatches:
        raise SystemExit("R source synchronization failed: " + "; ".join(mismatches))
    print("R source synchronization: ok")


if __name__ == "__main__":
    main()
