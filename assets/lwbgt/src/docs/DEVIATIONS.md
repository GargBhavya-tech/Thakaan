# Changes from the original C source

[UPSTREAM.md](UPSTREAM.md) identifies the retained oracle. The modified kernel
is `src/wbgt.c`, with a byte-identical copy in `r/src/wbgt.c`. Inspect the full
diff with:

```sh
git diff --no-index -- upstream/wbgt.c.original src/wbgt.c
```

| Change | Effect |
|---|---|
| Typed C declarations | Replace K&R definitions and implicit declarations. Scalar inputs still enter as `double` and round to `float`, preserving the calling convention. |
| Solar error handling | Return -1 and set all five outputs to -9999 instead of consuming unwritten solar outputs. |
| Scalar wind output at 2 m | Write the supplied speed rounded to `float`; the original left this output unwritten. Temperature and WBGT outputs are unchanged on the comparison corpus. |
| Shared atmospheric state | Prepare vapor pressure, dew point, emissivity, and long-wave terms once per row; reuse rounded viscosity and density within wet-bulb iterations. |
| Less radiation work | Hoist invariant solar terms and skip unused psychrometric radiation calculations. |
| Separate demonstration | Omit `main` and its I/O dependencies from library builds; initialize the demo's first temperature difference. |

Equations, constants, intermediate precision, WBGT weights, convergence rules,
wind-stability table, and solar-position date arithmetic are preserved.
Redundant internal helpers were removed; comment repairs in `esat` do not
change its formula.

Non-finite weather can behave differently. With NaN solar input, the original
psychrometric path fails while the derivative can return finite `Tpsy` after
skipping unused radiation work. Those inputs have separate current-API
consistency checks and no upstream bit-equivalence claim.

[BASELINE.md](../tests/BASELINE.md) records the finite-input oracle comparisons
and failure tests. [ABI.md](ABI.md) defines the public interface and failure
contract.
