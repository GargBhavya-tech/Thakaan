from __future__ import annotations

import ctypes
import unittest
from contextlib import ExitStack
from importlib import resources

from lwbgt import _ffi


class PackagedLibraryTests(unittest.TestCase):
    def test_expected_packaged_resource_is_loadable(self) -> None:
        resource = _ffi._library_resource()
        self.assertTrue(resource.is_file())
        with resources.as_file(resource) as path:
            ctypes.CDLL(str(path))

    def test_package_contains_exactly_one_native_runtime(self) -> None:
        suffixes = (".dll", ".dylib", ".so")
        libraries = [
            item.name
            for item in resources.files("lwbgt").iterdir()
            if item.is_file() and item.name.endswith(suffixes)
        ]
        self.assertEqual(libraries, [_ffi._library_filename()])

    def test_resource_context_is_retained_for_library_lifetime(self) -> None:
        self.assertIsInstance(_ffi._RESOURCE_STACK, ExitStack)
        self.assertIs(_ffi._library(), _ffi._library())


if __name__ == "__main__":
    unittest.main()
