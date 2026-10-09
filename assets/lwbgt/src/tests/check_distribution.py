"""Inspect built wheel and sdist contents for release-critical files."""

from __future__ import annotations

import glob
import sys
import tarfile
import zipfile
from pathlib import Path

LICENSE_FILES = {
    "LICENSE",
    "NOTICE",
    "src/LicenseRef-UChicago-Argonne-WBGT-1.1.txt",
}
SDIST_BUILD_FILES = {
    "Package.swift",
    "CMakeLists.txt",
    "pyproject.toml",
    "README.md",
    "CONTRIBUTING.md",
    "docs/ABI.md",
    "docs/COMPATIBILITY.md",
    "docs/COMPARISON.md",
    "docs/FAQ.md",
    "docs/README.md",
    "docs/RELEASING.md",
    "docs/UPSTREAM.md",
    "include/lwbgt.h",
    "src/lwbgt_ffi.c",
    "src/wbgt.c",
    "upstream/wbgt.c.original",
    "python/lwbgt/__init__.py",
    "python/lwbgt/_ffi.py",
    "python/lwbgt/_types.py",
    "r/DESCRIPTION",
    "r/NAMESPACE",
    "r/R/lwbgt.R",
    "r/src/wbgt.c",
    "r/src/lwbgt.h",
    "r/src/lwbgt_r.c",
    "tests/swiftpm/Package.swift",
    "tests/generate_cases.py",
    "tests/baseline.json",
    "tests/check_benchmark.py",
    "tests/compare_compilers.py",
    "tests/test_compiler_checks.py",
    "benchmarks/benchmark.c",
    "benchmarks/compare.py",
    "tests/branch_probe.c",
    "tests/esat_probe.c",
    "tests/swiftpm/Tests/CLWBGTConsumerTests/CLWBGTConsumerTests.swift",
}


def is_native_library(name: str) -> bool:
    basename = name.rsplit("/", 1)[-1].lower()
    return basename.endswith((".dll", ".dylib", ".so")) or ".so." in basename


def inspect_wheel(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    licenses = {
        name.split(".dist-info/licenses/", 1)[1]
        for name in names
        if ".dist-info/licenses/" in name and not name.endswith("/")
    }
    if licenses != LICENSE_FILES:
        raise RuntimeError(f"{path}: wheel licences differ: {sorted(licenses)}")
    libraries = [name for name in names if is_native_library(name)]
    if len(libraries) != 1 or not libraries[0].startswith("lwbgt/"):
        raise RuntimeError(f"{path}: expected one runtime library, got {libraries}")
    forbidden = [
        name for name in names if name.startswith(("include/", "lib/", "share/"))
    ]
    if forbidden:
        raise RuntimeError(f"{path}: wheel contains development files: {forbidden}")


def inspect_sdist(path: Path) -> None:
    with tarfile.open(path) as archive:
        members = archive.getnames()
    roots = {name.split("/", 1)[0] for name in members}
    if len(roots) != 1:
        raise RuntimeError(f"{path}: expected one sdist root, got {sorted(roots)}")
    root = roots.pop()
    names = {name.removeprefix(f"{root}/") for name in members}
    if any("__pycache__" in Path(name).parts for name in names):
        raise RuntimeError(f"{path}: sdist contains Python bytecode caches")
    required = LICENSE_FILES | SDIST_BUILD_FILES
    missing = sorted(required - names)
    if missing:
        raise RuntimeError(f"{path}: sdist is missing {missing}")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: check_distribution.py DIST...")
    paths = [Path(match) for argument in sys.argv[1:] for match in glob.glob(argument)]
    if not paths:
        raise RuntimeError("no distributions matched")
    for path in paths:
        if path.suffix == ".whl":
            inspect_wheel(path)
        elif path.name.endswith(".tar.gz"):
            inspect_sdist(path)
        else:
            raise RuntimeError(f"unsupported distribution: {path}")
        print(f"distribution contents: {path.name}: ok")


if __name__ == "__main__":
    main()
