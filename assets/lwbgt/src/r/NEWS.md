# lwbgt 1.2.0

- Add `calculate(input, psychrometric = FALSE)` to skip the independent
  psychrometric wet-bulb solve; its column is `NA`. The default remains `TRUE`.
- Synchronize private numerical helpers with the canonical C kernel.
- The internal native calculation routine now takes two arguments; existing
  calls to the exported R functions remain compatible.

# lwbgt 1.1.0

- Synchronize with the shared release. The R API and reference-compatible
  native calculation are unchanged.
- Carry the expanded shared numerical corpus and cross-platform validation
  improvements documented in the project changelog.

# lwbgt 1.0.1

- Relax R test-suite golden-value comparisons to native single-precision
  tolerance for portability to R builds without long-double support.

# lwbgt 1.0.0

- Stabilized the existing R interface and reference-compatible native v1
  calculation. Numerical results and the default calculation are unchanged.
- Future numerical revisions will use explicit versioned interfaces.

# lwbgt 0.4.3

- Synchronized the R package with project release 0.4.3. The direct C scalar
  API now writes estimated wind for 2 m input; the R output and WBGT results
  for valid inputs are unchanged.
- Expanded the retained-original comparison.

# lwbgt 0.4.2

- Synchronized the R package version with project release 0.4.2; the R API and
  native numerical behavior are unchanged from 0.4.1.

# lwbgt 0.4.1

- Declared the CRAN-facing package license as Apache License 2.0.

# lwbgt 0.4.0

- Added the first R interface with dependency-free vectorized
  `lwbgt_input()`, `calculate()`, and `esat()` functions.
- Added per-row validation, stable statuses, and R-native missing output
  handling.
- Bundled the reference-compatible native kernel directly into the package.
- Added direct GitHub installation and R-universe distribution.
