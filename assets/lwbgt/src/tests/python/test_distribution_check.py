from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


def load_distribution_checker():
    path = Path(__file__).parents[1] / "check_distribution.py"
    spec = importlib.util.spec_from_file_location("check_distribution", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DistributionCheckTests(unittest.TestCase):
    def test_wheel_license_directories_are_not_treated_as_files(self) -> None:
        checker = load_distribution_checker()
        with tempfile.TemporaryDirectory() as temporary:
            wheel = Path(temporary, "lwbgt-1.0.0-py3-none-test.whl")
            prefix = "lwbgt-1.0.0.dist-info/licenses/"
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr(prefix, "")
                archive.writestr(f"{prefix}src/", "")
                for license_file in checker.LICENSE_FILES:
                    archive.writestr(f"{prefix}{license_file}", "license")
                archive.writestr("lwbgt/liblwbgt.so", "native")
            checker.inspect_wheel(wheel)


if __name__ == "__main__":
    unittest.main()
