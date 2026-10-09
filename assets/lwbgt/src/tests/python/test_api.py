from __future__ import annotations

import ctypes
import struct
import unittest
from importlib.metadata import version

import lwbgt
from _fixtures import SINGAPORE, SOLVER_FAILURE
from lwbgt import _ffi


def float_bits(value: float) -> bytes:
    return struct.pack("=f", value)


class ApiTests(unittest.TestCase):
    def test_public_surface_and_version(self) -> None:
        self.assertEqual(lwbgt.__version__, version("lwbgt"))
        self.assertEqual(
            lwbgt.__all__, ["Input", "Result", "calculate", "calculate_batch", "esat"]
        )

    def test_singapore_scalar_matches_native_scalar_exactly(self) -> None:
        result = lwbgt.calculate(SINGAPORE)
        native_input = _ffi._as_native(SINGAPORE)
        values = [ctypes.c_float(SINGAPORE.wind_speed_m_s) for _ in range(5)]
        scalar = _ffi._library().calc_wbgt
        scalar.argtypes = (
            (ctypes.c_int,) * 7
            + (ctypes.c_double,) * 9
            + (ctypes.c_int,)
            + (ctypes.POINTER(ctypes.c_float),) * 5
        )
        scalar.restype = ctypes.c_int
        status = scalar(
            native_input.year,
            native_input.month,
            native_input.day,
            native_input.hour,
            native_input.minute,
            native_input.gmt_offset_hours,
            native_input.averaging_minutes,
            native_input.latitude_deg_north,
            native_input.longitude_deg_east,
            native_input.solar_w_m2,
            native_input.pressure_hpa,
            native_input.air_temperature_c,
            native_input.relative_humidity_percent,
            native_input.wind_speed_m_s,
            native_input.wind_height_m,
            native_input.vertical_temperature_difference_c,
            native_input.urban,
            *(ctypes.byref(value) for value in values),
        )
        direct = (status, *(value.value for value in values))
        wrapped = tuple(getattr(result, name) for name in result.__dataclass_fields__)
        self.assertEqual(direct[0], wrapped[0])
        self.assertEqual(
            [float_bits(value) for value in direct[1:]],
            [float_bits(value) for value in wrapped[1:]],
        )
        self.assertEqual(float_bits(result.wbgt_c).hex(), "59020242")

    def test_solver_failure_is_returned_without_policy_conversion(self) -> None:
        result = lwbgt.calculate(SOLVER_FAILURE)
        self.assertEqual(result.status, -1)
        self.assertEqual(result.globe_temperature_c, -9999.0)
        self.assertEqual(result.natural_wet_bulb_c, -9999.0)
        self.assertEqual(result.wbgt_c, -9999.0)

    def test_esat_water_and_ice_are_exact_native_float32_results(self) -> None:
        for phase, expected in ((0, "955ec440"), (1, "a559c440")):
            wrapped = lwbgt.esat(273.15, phase)
            direct = _ffi._library().esat(273.15, phase)
            self.assertEqual(float_bits(wrapped), float_bits(direct))
            self.assertEqual(float_bits(wrapped).hex(), expected)


if __name__ == "__main__":
    unittest.main()
