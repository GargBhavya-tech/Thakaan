from __future__ import annotations

import csv
import ctypes
import struct
import subprocess
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

import lwbgt
from _fixtures import NIGHT, SINGAPORE, SOLVER_FAILURE
from lwbgt import _ffi


def packed(result: lwbgt.Result) -> bytes:
    return struct.pack(
        "=i5f",
        result.status,
        result.estimated_wind_speed_m_s,
        result.globe_temperature_c,
        result.natural_wet_bulb_c,
        result.psychrometric_wet_bulb_c,
        result.wbgt_c,
    )


class BatchTests(unittest.TestCase):
    def test_empty_batch(self) -> None:
        self.assertEqual(lwbgt.calculate_batch([]), [])
        self.assertEqual(lwbgt.calculate_batch([], psychrometric=False), [])

    def test_psychrometric_opt_out_preserves_other_outputs(self) -> None:
        records = [NIGHT, SINGAPORE, SOLVER_FAILURE, replace(SINGAPORE, year=2050)]
        default = lwbgt.calculate_batch(records)
        self.assertEqual(default, lwbgt.calculate_batch(records, psychrometric=True))
        skipped = lwbgt.calculate_batch(iter(records), psychrometric=False)
        expected = [replace(result, psychrometric_wet_bulb_c=-9999.0) for result in default]
        self.assertEqual([packed(result) for result in skipped], [packed(result) for result in expected])
        self.assertEqual(skipped, [lwbgt.calculate(record, psychrometric=False) for record in records])

    def test_psychrometric_option_requires_bool(self) -> None:
        for value in [None, 0, 1, "false"]:
            with self.subTest(value=value):
                with self.assertRaises(TypeError):
                    lwbgt.calculate(SINGAPORE, psychrometric=value)
                with self.assertRaises(TypeError):
                    lwbgt.calculate_batch([], psychrometric=value)

    def test_one_record_batch_matches_scalar(self) -> None:
        self.assertEqual(
            lwbgt.calculate_batch([SINGAPORE]), [lwbgt.calculate(SINGAPORE)]
        )

    def test_multiple_records_preserve_order_and_caller_objects(self) -> None:
        records = [NIGHT, SINGAPORE, SOLVER_FAILURE]
        before = records.copy()
        results = lwbgt.calculate_batch(records)
        self.assertEqual(records, before)
        self.assertEqual([result.status for result in results], [0, 0, -1])
        self.assertEqual(results, [lwbgt.calculate(record) for record in records])

    def test_batch_matches_direct_native_batch_exactly(self) -> None:
        records = [SINGAPORE, NIGHT, SOLVER_FAILURE]
        count = len(records)
        inputs = (_ffi._InputV1 * count)(*map(_ffi._as_native, records))
        outputs = (_ffi._OutputV1 * count)()
        status = _ffi._library().lwbgt_calc_batch_v1(inputs, outputs, count)
        self.assertEqual(status, 0)
        direct = [_ffi._as_result(output) for output in outputs]
        wrapped = lwbgt.calculate_batch(records)
        self.assertEqual(
            [packed(result) for result in wrapped],
            [packed(result) for result in direct],
        )

    def test_batch_signature_and_invalid_argument_contract(self) -> None:
        function = _ffi._library().lwbgt_calc_batch_v1
        self.assertEqual(function.restype, ctypes.c_int)
        self.assertEqual(function(None, None, 0), 0)
        output = _ffi._OutputV1()
        self.assertEqual(function(None, ctypes.byref(output), 1), 1)
        extended = _ffi._library().lwbgt_calc_batch_ex_v1
        self.assertEqual(extended.restype, ctypes.c_int)
        self.assertEqual(extended(None, None, 0, 0), 0)
        self.assertEqual(extended(None, None, 0, 1), 0)
        self.assertEqual(extended(None, None, 0, 2), 1)

    def test_deterministic_case_set_matches_direct_native_batch_exactly(self) -> None:
        self.check_case_set(35976)

    def test_invalid_weather_matches_direct_native_batch_exactly(self) -> None:
        self.check_case_set(64, weather=True)

    def check_case_set(self, expected_count: int,
                       weather: bool = False) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary, "cases.csv")
            cases = Path(temporary, "weather-cases.csv") if weather else output
            arguments = ["--samples", "0", "--weather-output", str(cases)] if weather else []
            subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).parents[1] / "generate_cases.py"),
                    str(output),
                    *arguments,
                ],
                check=True,
            )
            with cases.open(newline="", encoding="ascii") as stream:
                records = [
                    lwbgt.Input(
                        year=int(row["year"]),
                        month=int(row["month"]),
                        day=int(row["day"]),
                        hour=int(row["hour"]),
                        minute=int(row["minute"]),
                        gmt_offset_hours=int(row["gmt"]),
                        averaging_minutes=int(row["avg"]),
                        urban=int(row["urban"]),
                        latitude_deg_north=float(row["lat"]),
                        longitude_deg_east=float(row["lon"]),
                        solar_w_m2=float(row["solar"]),
                        pressure_hpa=float(row["pressure"]),
                        air_temperature_c=float(row["air"]),
                        relative_humidity_percent=float(row["rh"]),
                        wind_speed_m_s=float(row["wind"]),
                        wind_height_m=float(row["wind_height"]),
                        vertical_temperature_difference_c=float(row["vertical_delta"]),
                    )
                    for row in csv.DictReader(stream)
                ]
        count = len(records)
        self.assertEqual(count, expected_count)
        inputs = (_ffi._InputV1 * count)(*map(_ffi._as_native, records))
        outputs = (_ffi._OutputV1 * count)()
        self.assertEqual(_ffi._library().lwbgt_calc_batch_v1(inputs, outputs, count), 0)
        direct = [_ffi._as_result(output) for output in outputs]
        wrapped = lwbgt.calculate_batch(records)
        self.assertEqual(
            [packed(result) for result in wrapped],
            [packed(result) for result in direct],
        )
        self.assertEqual(_ffi._library().lwbgt_calc_batch_ex_v1(inputs, outputs, count, 1), 0)
        skipped = lwbgt.calculate_batch(records, psychrometric=False)
        expected = [replace(result, psychrometric_wet_bulb_c=-9999.0) for result in direct]
        self.assertEqual([packed(result) for result in skipped], [packed(result) for result in expected])
        self.assertEqual(
            [packed(result) for result in skipped],
            [packed(_ffi._as_result(output)) for output in outputs],
        )


if __name__ == "__main__":
    unittest.main()
