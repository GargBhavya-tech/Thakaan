# FAQ

## What does lwbgt calculate?

Outdoor WBGT using the Liljegren model, plus globe, natural wet-bulb and
psychrometric wet-bulb temperatures, and estimated 2 m wind speed. It returns
calculation results; callers choose heat-risk categories and exposure policies.

## Which inputs and units are required?

Date/time, GMT offset, averaging interval, location, radiation, pressure,
air temperature, humidity, wind speed/height, urban/rural flag, and vertical
temperature difference. [ABI.md](ABI.md) defines every field and unit.
The native, Python, and Julia APIs do not convert units or supply defaults;
[INPUTS.md](INPUTS.md) explains conditional fallbacks.

## How do I use Python or array-based data?

Install with `python -m pip install lwbgt`, create an `Input`, and call
`calculate` or `calculate_batch`. The batch function submits all records in
one serial native call. The API accepts records, not NumPy, pandas, or xarray
objects; adapt those in your application. See the [Python example](../README.md#python).

## How do I use Julia?

Install LWBGT.jl from General with `Pkg.add("LWBGT")` on Julia 1.10 or later.
The native library is installed automatically through `lwbgt_jll`. See the
[Julia example](../README.md#julia) for scalar and batch calls.

## How are failures handled?

Native, Python, and Julia calls return a per-row status and the native failure
values.
R validates inputs and replaces failed-row numerical outputs with `NA`.
See [the failure contract](ABI.md#batch-call-contract) and [R quick start](../r/README.md).

## Can I substitute another WBGT package?

Only after matching the method, units, and preprocessing and comparing results.
No cross-package equivalence is claimed. [COMPARISON.md](COMPARISON.md) covers
package scope; [COMPATIBILITY.md](COMPATIBILITY.md) covers the retained-original
comparison and its limits.

## Which languages are supported?

C/C++ through the native ABI, Python and R through official bindings,
Julia through [LWBGT.jl](https://github.com/zyf0717/LWBGT.jl), and Swift through
the `CLWBGT` SwiftPM product. See the
[README](../README.md) for installation and usage.
