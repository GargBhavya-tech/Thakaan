"""Private ctypes binding to the bundled lwbgt FFI v1 library."""

from __future__ import annotations

import ctypes
import sys
from collections.abc import Iterable
from contextlib import ExitStack
from importlib import resources
from threading import Lock
from typing import Any

from ._types import Input, Result


class _InputV1(ctypes.Structure):
    _fields_ = [
        ("year", ctypes.c_int32),
        ("month", ctypes.c_int32),
        ("day", ctypes.c_int32),
        ("hour", ctypes.c_int32),
        ("minute", ctypes.c_int32),
        ("gmt_offset_hours", ctypes.c_int32),
        ("averaging_minutes", ctypes.c_int32),
        ("urban", ctypes.c_int32),
        ("latitude_deg_north", ctypes.c_double),
        ("longitude_deg_east", ctypes.c_double),
        ("solar_w_m2", ctypes.c_double),
        ("pressure_hpa", ctypes.c_double),
        ("air_temperature_c", ctypes.c_double),
        ("relative_humidity_percent", ctypes.c_double),
        ("wind_speed_m_s", ctypes.c_double),
        ("wind_height_m", ctypes.c_double),
        ("vertical_temperature_difference_c", ctypes.c_double),
    ]


class _OutputV1(ctypes.Structure):
    _fields_ = [
        ("status", ctypes.c_int32),
        ("estimated_wind_speed_m_s", ctypes.c_float),
        ("globe_temperature_c", ctypes.c_float),
        ("natural_wet_bulb_c", ctypes.c_float),
        ("psychrometric_wet_bulb_c", ctypes.c_float),
        ("wbgt_c", ctypes.c_float),
    ]


if ctypes.sizeof(_InputV1) != 104:
    raise RuntimeError("lwbgt_input_v1 ctypes layout is not 104 bytes")
if ctypes.sizeof(_OutputV1) != 24:
    raise RuntimeError("lwbgt_output_v1 ctypes layout is not 24 bytes")


_RESOURCE_STACK = ExitStack()
_LIBRARY: ctypes.CDLL | None = None
_LIBRARY_LOCK = Lock()


def _library_filename() -> str:
    if sys.platform == "win32":
        return "lwbgt.dll"
    if sys.platform == "darwin":
        return "liblwbgt.dylib"
    return "liblwbgt.so"


def _library_resource() -> Any:
    return resources.files("lwbgt").joinpath(_library_filename())


def _configure_library(library: ctypes.CDLL) -> None:
    library.esat.argtypes = (ctypes.c_double, ctypes.c_int)
    library.esat.restype = ctypes.c_float
    library.lwbgt_calc_batch_v1.argtypes = (
        ctypes.POINTER(_InputV1),
        ctypes.POINTER(_OutputV1),
        ctypes.c_size_t,
    )
    library.lwbgt_calc_batch_v1.restype = ctypes.c_int
    library.lwbgt_calc_batch_ex_v1.argtypes = (
        *library.lwbgt_calc_batch_v1.argtypes,
        ctypes.c_uint32,
    )
    library.lwbgt_calc_batch_ex_v1.restype = ctypes.c_int


def _library() -> ctypes.CDLL:
    global _LIBRARY

    if _LIBRARY is not None:
        return _LIBRARY
    with _LIBRARY_LOCK:
        if _LIBRARY is not None:
            return _LIBRARY
        resource = _library_resource()
        if not resource.is_file():
            raise RuntimeError(f"bundled lwbgt runtime is missing: {resource}")
        path = _RESOURCE_STACK.enter_context(resources.as_file(resource))
        try:
            library = ctypes.CDLL(str(path))
            _configure_library(library)
        except (AttributeError, OSError) as error:
            raise RuntimeError(f"cannot load bundled lwbgt runtime: {path}") from error
        _LIBRARY = library
        return library


def _as_native(record: Input) -> _InputV1:
    if not isinstance(record, Input):
        raise TypeError(f"record must be lwbgt.Input, not {type(record).__name__}")
    return _InputV1(*(getattr(record, name) for name, _ in _InputV1._fields_))


def _as_result(output: _OutputV1) -> Result:
    return Result(*(getattr(output, name) for name, _ in _OutputV1._fields_))


def calculate(record: Input, *, psychrometric: bool = True) -> Result:
    """Calculate one record; disabling psychrometric wet-bulb returns -9999 for it."""

    if not isinstance(psychrometric, bool):
        raise TypeError("psychrometric must be bool")
    native_input = _as_native(record)
    native_output = _OutputV1()
    status = _library().lwbgt_calc_batch_ex_v1(
        ctypes.byref(native_input),
        ctypes.byref(native_output),
        1,
        0 if psychrometric else 1,
    )
    if status != 0:
        raise RuntimeError(
            f"lwbgt_calc_batch_ex_v1 rejected a valid wrapper call: {status}"
        )
    return _as_result(native_output)


def calculate_batch(
    records: Iterable[Input], *, psychrometric: bool = True
) -> list[Result]:
    """Calculate records in input order; skipped psychrometric wet-bulb is -9999."""

    if not isinstance(psychrometric, bool):
        raise TypeError("psychrometric must be bool")
    native_records = tuple(_as_native(record) for record in records)
    if not native_records:
        return []
    count = len(native_records)
    inputs = (_InputV1 * count)(*native_records)
    outputs = (_OutputV1 * count)()
    status = _library().lwbgt_calc_batch_ex_v1(
        inputs, outputs, count, 0 if psychrometric else 1
    )
    if status != 0:
        raise RuntimeError(
            f"lwbgt_calc_batch_ex_v1 rejected a valid wrapper call: {status}"
        )
    return [_as_result(output) for output in outputs]


def esat(temperature_k: float, phase: int = 0) -> float:
    """Return native saturation vapour pressure for temperature in kelvin."""

    return float(_library().esat(temperature_k, phase))
