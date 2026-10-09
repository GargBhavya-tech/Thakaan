# SPDX-License-Identifier: Apache-2.0

library(lwbgt)

assert_float_equal <- function(actual, expected) {
    stopifnot(isTRUE(all.equal(
        actual,
        expected,
        tolerance = 2^-23 # Native C float precision (IEEE binary32 epsilon).
    )))
}

assert_error <- function(expr) {
    failed <- tryCatch({
        force(expr)
        FALSE
    }, error = function(error) TRUE)
    stopifnot(failed)
}

assert_warning <- function(expr) {
    warned <- FALSE
    value <- withCallingHandlers(
        expr,
        warning = function(warning) {
            warned <<- TRUE
            invokeRestart("muffleWarning")
        }
    )
    stopifnot(warned)
    value
}

singapore <- lwbgt_input(
    year = 2024,
    month = 4,
    day = 15,
    hour = 14,
    minute = 30,
    gmt_offset_hours = 8,
    averaging_minutes = 60,
    urban = 1,
    latitude_deg_north = 1.3521,
    longitude_deg_east = 103.8198,
    solar_w_m2 = 742,
    pressure_hpa = 1008.4,
    air_temperature_c = 32.1,
    relative_humidity_percent = 68,
    wind_speed_m_s = 2.8,
    wind_height_m = 10,
    vertical_temperature_difference_c = -0.4
)

stopifnot(
    is.data.frame(singapore),
    nrow(singapore) == 1L,
    identical(
        names(singapore),
        c(
            "year", "month", "day", "hour", "minute", "gmt_offset_hours",
            "averaging_minutes", "urban", "latitude_deg_north",
            "longitude_deg_east", "solar_w_m2", "pressure_hpa",
            "air_temperature_c", "relative_humidity_percent",
            "wind_speed_m_s", "wind_height_m",
            "vertical_temperature_difference_c"
        )
    )
)

positional <- do.call(lwbgt_input, unname(as.list(singapore)))
stopifnot(identical(positional, singapore))

singapore_result <- calculate(singapore)
stopifnot(
    is.data.frame(singapore_result),
    identical(
        names(singapore_result),
        c(
            "status", "status_message", "estimated_wind_speed_m_s",
            "globe_temperature_c", "natural_wet_bulb_c",
            "psychrometric_wet_bulb_c", "wbgt_c"
        )
    ),
    singapore_result$status == 0L,
    singapore_result$status_message == "ok"
)
assert_float_equal(singapore_result$wbgt_c, 32.50229263305664)

night <- singapore
night$hour <- 2
night$solar_w_m2 <- 0
night$pressure_hpa <- 1009.7
night$air_temperature_c <- 27
night$relative_humidity_percent <- 88
night$wind_speed_m_s <- 1.1
night$vertical_temperature_difference_c <- 0.2

batch <- rbind(singapore, night)
row.names(batch) <- c("day", "night")
batch$unrelated <- c("first", "second")
batch_result <- calculate(batch)
stopifnot(
    identical(batch_result$status, c(0L, 0L)),
    identical(row.names(batch_result), c("day", "night")),
    identical(batch_result$wbgt_c[[1L]], singapore_result$wbgt_c[[1L]])
)
assert_float_equal(batch_result$wbgt_c[[2L]], 25.700485229492188)

skipped <- calculate(batch, psychrometric = FALSE)
stopifnot(
    identical(batch_result, calculate(batch, psychrometric = TRUE)),
    all(is.na(skipped$psychrometric_wet_bulb_c)),
    identical(skipped[-6L], batch_result[-6L]),
    identical(calculate(batch)$status, c(0L, 0L))
)
for (value in list(NA, NULL, logical(), c(TRUE, FALSE), 1, "FALSE")) {
    assert_error(calculate(batch, psychrometric = value))
}
stopifnot(nrow(calculate(batch[FALSE, ], psychrometric = FALSE)) == 0L)

recycled_arguments <- as.list(singapore)
recycled_arguments$year <- c(2023, 2024)
recycled <- do.call(lwbgt_input, recycled_arguments)
stopifnot(nrow(recycled) == 2L, identical(recycled$year, c(2023, 2024)))

empty_result <- calculate(singapore[FALSE, ])
stopifnot(
    is.data.frame(empty_result),
    nrow(empty_result) == 0L,
    is.integer(empty_result$status),
    is.character(empty_result$status_message)
)

for (name in names(singapore)) {
    missing_input <- singapore
    missing_input[[name]] <- NA_real_
    missing_result <- calculate(missing_input)
    stopifnot(
        missing_result$status == 1L,
        missing_result$status_message == paste("missing input:", name),
        all(is.na(missing_result[3:7]))
    )
}

invalid_cases <- list(
    list("year", 1949), list("year", 2050), list("year", 2024.5),
    list("month", -1), list("month", 13), list("month", 1.5),
    list("day", 0), list("day", 1.5),
    list("hour", -1), list("hour", 24), list("hour", 1.5),
    list("minute", -1), list("minute", 60), list("minute", 1.5),
    list("gmt_offset_hours", -13), list("gmt_offset_hours", 15),
    list("gmt_offset_hours", 0.5),
    list("averaging_minutes", -1), list("averaging_minutes", 1441),
    list("averaging_minutes", 0.5),
    list("urban", -1), list("urban", 2), list("urban", 0.5),
    list("latitude_deg_north", -90.01), list("latitude_deg_north", 90.01),
    list("longitude_deg_east", -180.01), list("longitude_deg_east", 180.01),
    list("solar_w_m2", -1), list("solar_w_m2", Inf),
    list("pressure_hpa", 0), list("pressure_hpa", Inf),
    list("air_temperature_c", -273.15), list("air_temperature_c", Inf),
    list("relative_humidity_percent", -1),
    list("relative_humidity_percent", 101),
    list("wind_speed_m_s", -1), list("wind_speed_m_s", Inf),
    list("wind_height_m", 0), list("wind_height_m", Inf),
    list("vertical_temperature_difference_c", Inf)
)
for (case in invalid_cases) {
    name <- case[[1L]]
    invalid_input <- singapore
    invalid_input[[name]] <- case[[2L]]
    invalid_result <- calculate(invalid_input)
    stopifnot(
        invalid_result$status == 2L,
        startsWith(invalid_result$status_message, "invalid input:"),
        all(is.na(invalid_result[3:7]))
    )
}

valid_boundaries <- list(
    year = c(1950, 2049), month = c(0, 12), hour = c(0, 23),
    minute = c(0, 59), gmt_offset_hours = c(-12, 14),
    averaging_minutes = c(0, 1440), urban = c(0, 1),
    latitude_deg_north = c(-90, 90), longitude_deg_east = c(-180, 180),
    solar_w_m2 = 0, pressure_hpa = 1e-30,
    air_temperature_c = -273.149, relative_humidity_percent = c(0, 100),
    wind_speed_m_s = 0, wind_height_m = 1e-30,
    vertical_temperature_difference_c = c(-1e30, 1e30)
)
for (name in names(valid_boundaries)) {
    for (value in valid_boundaries[[name]]) {
        boundary_input <- singapore
        boundary_input[[name]] <- value
        stopifnot(calculate(boundary_input)$status != 2L)
    }
}

invalid_day <- singapore
invalid_day$month <- 2
invalid_day$day <- 30
stopifnot(calculate(invalid_day)$status == 2L)

leap_day <- singapore
leap_day$year <- 2024
leap_day$month <- 2
leap_day$day <- 29
stopifnot(calculate(leap_day)$status != 2L)
day_of_year <- leap_day
day_of_year$month <- 0
day_of_year$day <- 366
stopifnot(calculate(day_of_year)$status != 2L)
day_of_year$year <- 2023
stopifnot(calculate(day_of_year)$status == 2L)
solver_failure <- singapore
solver_failure$month <- 3
solver_failure$day <- 20
solver_failure$hour <- 12
solver_failure$minute <- 0
solver_failure$gmt_offset_hours <- 0
solver_failure$averaging_minutes <- 0
solver_failure$urban <- 0
solver_failure$latitude_deg_north <- 0
solver_failure$longitude_deg_east <- 0
solver_failure$solar_w_m2 <- 1000
solver_failure$pressure_hpa <- 300
solver_failure$air_temperature_c <- 60
solver_failure$relative_humidity_percent <- 100
solver_failure$wind_speed_m_s <- 0.129
solver_failure$wind_height_m <- 2
solver_failure$vertical_temperature_difference_c <- 0
solver_result <- calculate(solver_failure)
stopifnot(
    solver_result$status == -1L,
    solver_result$status_message == "solver did not converge",
    all(is.na(solver_result[3:7]))
)

assert_float_equal(esat(273.15), 6.136545658111572)
assert_float_equal(esat(273.15, 1L), 6.1359429359436035)
assert_float_equal(esat(c(273.15, NA_real_)), c(6.136545658111572, NA_real_))
stopifnot(
    identical(esat(numeric(), integer()), numeric()),
    length(esat(c(273.15, 273.15), 0L)) == 2L
)
invalid_esat <- assert_warning(esat(c(0, 273.15), c(0, 2)))
stopifnot(all(is.na(invalid_esat)))

assert_error(calculate(list()))
assert_error(calculate(singapore[-1L]))
bad_type <- singapore
bad_type$year <- "2024"
assert_error(calculate(bad_type))
mismatched_arguments <- as.list(singapore)
mismatched_arguments$year <- 1:2
mismatched_arguments$month <- 1:3
assert_error(do.call(lwbgt_input, mismatched_arguments))
assert_error(esat(1:2, 0:2))
assert_error(.Call("lwbgt_calculate", as.list(singapore), PACKAGE = "lwbgt"))
