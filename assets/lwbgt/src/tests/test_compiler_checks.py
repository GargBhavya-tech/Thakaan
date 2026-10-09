"""Regression tests for cross-compiler diagnostics and Windows export parsing."""

import unittest
from unittest.mock import patch

from check_exports import EXPECTED, exported
from compare_compilers import compare_outputs


class CompilerChecks(unittest.TestCase):
    def test_rounding_difference_is_reported_without_a_tolerance(self):
        report = compare_outputs(b"case_id,status,Tg\na,0,3f800000\n",
                                 b"case_id,status,Tg\na,0,3f800001\n", {"a": "nominal"})
        self.assertTrue(report["status_and_classification_match"])
        self.assertFalse(report["finite_values_match"])
        self.assertEqual(report["fields"]["Tg"]["max_ulp_difference"], 1)
        self.assertEqual(report["fields"]["Tg"]["max_absolute_difference"], 2**-23)
        self.assertEqual(report["cohorts"]["nominal"]["differing_rows"], 1)

    def test_status_and_special_value_changes_are_rejected(self):
        for expected, actual in [("c61c3c00", "3f800000"), ("7fc00000", "3f800000"),
                                 ("7f800000", "ff800000")]:
            with self.subTest(expected=expected, actual=actual):
                report = compare_outputs(f"case_id,status,Tg\na,0,{expected}\n".encode(),
                                         f"case_id,status,Tg\na,-1,{actual}\n".encode())
                self.assertFalse(report["status_and_classification_match"])
                self.assertEqual(report["status_mismatches"], 1)
                self.assertEqual(report["classification_mismatch_rows"], 1)

    def test_nan_payloads_and_signed_zero_are_reported(self):
        report = compare_outputs(b"case_id,esat\na,7fc00000\nb,80000000\n",
                                 b"case_id,esat\na,ffc00000\nb,00000000\n")
        self.assertTrue(report["status_and_classification_match"])
        self.assertEqual(report["fields"]["esat"]["different_bits"], 2)
        self.assertEqual(report["fields"]["esat"]["max_ulp_difference"], 0)
        self.assertTrue(report["finite_values_match"])

    def test_missing_reordered_or_malformed_rows_fail(self):
        expected = b"case_id,status,Tg\na,0,3f800000\nb,0,3f800000\n"
        for actual in [b"case_id,status,Tg\na,0,3f800000\n",
                       b"case_id,status,Tg\nb,0,3f800000\na,0,3f800000\n",
                       b"case_id,status,Tg\na,0\nb,0,3f800000\n"]:
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                compare_outputs(expected, actual)

    def test_dumpbin_exports(self):
        output = """ordinal hint RVA      name
                  1    0 00001000 calc_wbgt
                  2    1 00002000 esat
                  3    2 00003000 lwbgt_calc_batch_v1
                  4    3 00004000 lwbgt_calc_batch_ex_v1
        Summary
                 1000 .data
        """
        with patch("check_exports.run", return_value=output) as run:
            self.assertEqual(exported("Windows", "lwbgt.dll", "", "dumpbin.exe"), EXPECTED)
            run.assert_called_once_with("dumpbin.exe", "/nologo", "/exports", "lwbgt.dll")


if __name__ == "__main__":
    unittest.main()
