from __future__ import annotations

from lwbgt import Input

SINGAPORE = Input(
    year=2024,
    month=4,
    day=15,
    hour=14,
    minute=30,
    gmt_offset_hours=8,
    averaging_minutes=60,
    urban=1,
    latitude_deg_north=1.3521,
    longitude_deg_east=103.8198,
    solar_w_m2=742.0,
    pressure_hpa=1008.4,
    air_temperature_c=32.1,
    relative_humidity_percent=68.0,
    wind_speed_m_s=2.8,
    wind_height_m=10.0,
    vertical_temperature_difference_c=-0.4,
)

NIGHT = Input(
    year=2024,
    month=4,
    day=15,
    hour=2,
    minute=30,
    gmt_offset_hours=8,
    averaging_minutes=60,
    urban=1,
    latitude_deg_north=1.3521,
    longitude_deg_east=103.8198,
    solar_w_m2=0.0,
    pressure_hpa=1009.7,
    air_temperature_c=27.0,
    relative_humidity_percent=88.0,
    wind_speed_m_s=1.1,
    wind_height_m=10.0,
    vertical_temperature_difference_c=0.2,
)

SOLVER_FAILURE = Input(
    year=2024,
    month=3,
    day=20,
    hour=12,
    minute=0,
    gmt_offset_hours=0,
    averaging_minutes=0,
    urban=0,
    latitude_deg_north=0.0,
    longitude_deg_east=0.0,
    solar_w_m2=1000.0,
    pressure_hpa=300.0,
    air_temperature_c=60.0,
    relative_humidity_percent=100.0,
    wind_speed_m_s=0.129,
    wind_height_m=2.0,
    vertical_temperature_difference_c=0.0,
)
