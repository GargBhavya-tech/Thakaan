from __future__ import annotations

import ctypes
import unittest

from lwbgt import _ffi


class LayoutTests(unittest.TestCase):
    def test_structure_sizes(self) -> None:
        self.assertEqual(ctypes.sizeof(_ffi._InputV1), 104)
        self.assertEqual(ctypes.sizeof(_ffi._OutputV1), 24)

    def test_input_offsets_match_abi_v1(self) -> None:
        expected = {
            "year": 0,
            "month": 4,
            "day": 8,
            "hour": 12,
            "minute": 16,
            "gmt_offset_hours": 20,
            "averaging_minutes": 24,
            "urban": 28,
            "latitude_deg_north": 32,
            "longitude_deg_east": 40,
            "solar_w_m2": 48,
            "pressure_hpa": 56,
            "air_temperature_c": 64,
            "relative_humidity_percent": 72,
            "wind_speed_m_s": 80,
            "wind_height_m": 88,
            "vertical_temperature_difference_c": 96,
        }
        self.assertEqual(
            {
                name: getattr(_ffi._InputV1, name).offset
                for name, _ in _ffi._InputV1._fields_
            },
            expected,
        )

    def test_output_offsets_match_abi_v1(self) -> None:
        expected = {
            "status": 0,
            "estimated_wind_speed_m_s": 4,
            "globe_temperature_c": 8,
            "natural_wet_bulb_c": 12,
            "psychrometric_wet_bulb_c": 16,
            "wbgt_c": 20,
        }
        self.assertEqual(
            {
                name: getattr(_ffi._OutputV1, name).offset
                for name, _ in _ffi._OutputV1._fields_
            },
            expected,
        )


if __name__ == "__main__":
    unittest.main()
