# lwbgt

`lwbgt` provides dependency-free, vectorized R access to the Liljegren WBGT
kernel. Input names and units match the native and Python APIs.

Install from CRAN:

```r
install.packages("lwbgt")
```

If CRAN is unavailable, use R-universe:

```r
install.packages("lwbgt", repos = "https://zyf0717.r-universe.dev")
```

```r
library(lwbgt)

input <- lwbgt_input(
    year = 2024, month = 4, day = 15, hour = 14, minute = 30,
    gmt_offset_hours = 8, averaging_minutes = 60, urban = 1,
    latitude_deg_north = 1.3521, longitude_deg_east = 103.8198,
    solar_w_m2 = 742, pressure_hpa = 1008.4,
    air_temperature_c = 32.1, relative_humidity_percent = 68,
    wind_speed_m_s = 2.8, wind_height_m = 10,
    vertical_temperature_difference_c = 1
)
calculate(input)
esat(273.15)
```

`calculate(input, psychrometric = FALSE)` skips the independent psychrometric
wet-bulb solve and returns `NA` for that column. It is calculated by default;
the other outputs retain their existing behavior.

Rows with missing, invalid, or non-convergent inputs return a nonzero status
and `NA` numerical outputs without preventing other rows from being calculated.
