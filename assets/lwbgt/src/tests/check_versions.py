"""Fail when release versions disagree across supported metadata."""

from __future__ import annotations

import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def match(path: str, pattern: str) -> str:
    text = (ROOT / path).read_text(encoding="utf-8")
    found = re.search(pattern, text, re.MULTILINE)
    if not found:
        raise RuntimeError(f"cannot find version in {path}")
    return found.group(1)


def main() -> None:
    versions = {
        "CMakeLists.txt": match("CMakeLists.txt", r"project\(lwbgt VERSION ([0-9.]+)"),
        "pyproject.toml": match("pyproject.toml", r'^version = "([0-9.]+)"$'),
        "python/lwbgt/__init__.py": match(
            "python/lwbgt/__init__.py", r'^__version__ = "([0-9.]+)"$'
        ),
        "r/DESCRIPTION": match("r/DESCRIPTION", r"^Version: ([0-9.]+)$"),
        "CITATION.cff": match("CITATION.cff", r"^version: ([0-9.]+)$"),
        "README.md": match(
            "README.md", r"\*\*(?:Release status|Version): v([0-9.]+)\.\*\*"
        ),
        "CHANGELOG.md": match("CHANGELOG.md", r"^## v([0-9.]+)"),
        "docs/COMPARISON.md": match(
            "docs/COMPARISON.md", r"documented public scope of `lwbgt` ([0-9.]+)"
        ),
        "r/NEWS.md": match("r/NEWS.md", r"^# lwbgt ([0-9.]+)$"),
        "tests/RELEASE.md": match("tests/RELEASE.md", r"^# v([0-9.]+) release"),
    }
    header = (
        match("include/lwbgt.h", r"LWBGT_VERSION_MAJOR ([0-9]+)"),
        match("include/lwbgt.h", r"LWBGT_VERSION_MINOR ([0-9]+)"),
        match("include/lwbgt.h", r"LWBGT_VERSION_PATCH ([0-9]+)"),
    )
    versions["include/lwbgt.h"] = ".".join(header)
    expected = next(iter(versions.values()))
    disagreements = {
        path: version for path, version in versions.items() if version != expected
    }
    if disagreements:
        raise SystemExit(f"version mismatch: expected {expected}; got {disagreements}")

    tag = (
        os.environ.get("GITHUB_REF_NAME")
        if os.environ.get("GITHUB_REF_TYPE") == "tag"
        else None
    )
    if tag is not None and tag != f"v{expected}":
        raise SystemExit(f"release tag {tag!r} does not match v{expected}")
    print(f"version coherence: {expected}")


if __name__ == "__main__":
    main()
