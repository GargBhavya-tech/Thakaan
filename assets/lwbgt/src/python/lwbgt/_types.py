"""Typed records for the public lwbgt Python API."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Input:
    """One ABI v1 calculation input, with units encoded in field names.

    ``vertical_temperature_difference_c`` is the upper-minus-lower difference.
    Only whether it is less than zero or not is used. It can affect results
    only for rural nighttime records when ``wind_height_m`` differs from 2 m
    and ``wind_speed_m_s`` is below 2.5.
    """

    year: int
    month: int
    day: int
    hour: int
    minute: int
    gmt_offset_hours: int
    averaging_minutes: int
    urban: int
    latitude_deg_north: float
    longitude_deg_east: float
    solar_w_m2: float
    pressure_hpa: float
    air_temperature_c: float
    relative_humidity_percent: float
    wind_speed_m_s: float
    wind_height_m: float
    vertical_temperature_difference_c: float


@dataclass(frozen=True, slots=True)
class Result:
    """One ABI v1 calculation result."""

    status: int
    estimated_wind_speed_m_s: float
    globe_temperature_c: float
    natural_wet_bulb_c: float
    psychrometric_wet_bulb_c: float
    wbgt_c: float
