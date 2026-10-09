# lwbgt, pywbgt, and thermofeel

This page compares the documented public scope of `lwbgt` 1.2.0, `pywbgt`
3.0.7, and `thermofeel` 2.3.0. Third-party details were checked on 2026-09-22.

| Aspect | lwbgt | pywbgt | thermofeel |
|---|---|---|---|
| Scope | Native Liljegren kernel for embedding | Python WBGT workflow with selectable methods | Multiple heat/cold comfort indices |
| WBGT methods | Preserved Liljegren v1.1 calculation | `liljegren`, `bernard`, `dimiceli`, `dimiceli_nws` | `calculate_wbgt_simple`, `calculate_wbgt`, `calculate_wbgt_liljegren` |
| Interfaces | C ABI, FFI batch, Python, R, Julia (LWBGT.jl), SwiftPM, CMake, `pkg-config` | Python; arrays and xarray datasets | Python; NumPy-compatible arrays |
| Units | Explicit required units; caller converts | Pint/MetPy quantities; package converts | Numeric arrays in each function's documented units |
| Python dependencies | Standard library only | NumPy, Numba, MetPy, xarray, Pint, pandas, pvlib | NumPy |
| Preprocessing | Caller owns ingestion and missing-data policy | Unit-aware and xarray-oriented handling | Caller supplies inputs; supporting meteorological functions are available |

Use `lwbgt` for its native compatibility contract, `pywbgt` for a higher-level
WBGT workflow, or `thermofeel` for a broader thermal-comfort workflow. Pin
versions and methods, match units and preprocessing, and compare intermediate
and final results before substituting packages. This table is not a numerical
benchmark or an equivalence claim.

## Sources

- `lwbgt`: [README](../README.md), [ABI](ABI.md), [package metadata](../Package.swift),
  [Julia binding](https://github.com/zyf0717/LWBGT.jl)
- `pywbgt` 3.0.7: [README](https://github.com/kwodzicki/pywbgt/blob/v3.0.7/README.md),
  [methods](https://github.com/kwodzicki/pywbgt/blob/v3.0.7/src/pywbgt/constants.py),
  [metadata](https://github.com/kwodzicki/pywbgt/blob/v3.0.7/pyproject.toml)
- `thermofeel` 2.3.0: [WBGT functions](https://github.com/ecmwf/thermofeel/blob/2.3.0/thermofeel/thermofeel.py),
  [metadata](https://github.com/ecmwf/thermofeel/blob/2.3.0/pyproject.toml)
