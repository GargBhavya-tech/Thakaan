# SPDX-License-Identifier: Apache-2.0

.lwbgt_input_names <- c(
    "year",
    "month",
    "day",
    "hour",
    "minute",
    "gmt_offset_hours",
    "averaging_minutes",
    "urban",
    "latitude_deg_north",
    "longitude_deg_east",
    "solar_w_m2",
    "pressure_hpa",
    "air_temperature_c",
    "relative_humidity_percent",
    "wind_speed_m_s",
    "wind_height_m",
    "vertical_temperature_difference_c"
)

.recycle_lwbgt_fields <- function(fields) {
    field_lengths <- lengths(fields)
    if (any(field_lengths == 0L)) {
        if (!all(field_lengths %in% c(0L, 1L))) {
            stop("input lengths must be zero, one, or a common length", call. = FALSE)
        }
        size <- 0L
    } else {
        size <- max(field_lengths)
        if (any(!field_lengths %in% c(1L, size))) {
            stop("input lengths must be one or a common length", call. = FALSE)
        }
    }
    lapply(fields, rep, length.out = size)
}

#' Construct WBGT input records
#'
#' Creates a plain data frame whose columns map directly to the native input
#' contract. All arguments are required. Scalar arguments are recycled to the
#' common length of the non-scalar arguments.
#'
#' @param year Integer-valued Gregorian year from 1950 through 2049.
#' @param month,day,hour,minute Integer-valued local date and time fields.
#'   A `month` of zero makes `day` a day-of-year value.
#' @param gmt_offset_hours Local standard time minus GMT, in whole hours.
#' @param averaging_minutes Input averaging interval, in minutes.
#' @param urban Zero for rural wind scaling or one for urban wind scaling.
#' @param latitude_deg_north,longitude_deg_east Geographic coordinates in
#'   degrees north and degrees east.
#' @param solar_w_m2 Solar irradiance in watts per square metre.
#' @param pressure_hpa Barometric pressure in hectopascals.
#' @param air_temperature_c Dry-bulb air temperature in degrees Celsius.
#' @param relative_humidity_percent Relative humidity in percent.
#' @param wind_speed_m_s Wind speed in metres per second.
#' @param wind_height_m Wind measurement height in metres.
#' @param vertical_temperature_difference_c Upper-minus-lower temperature
#'   difference in degrees Celsius. Only whether it is `< 0` or `>= 0` is used.
#'   It can affect results only for rural nighttime records when `wind_height_m`
#'   differs from 2 m and `wind_speed_m_s` is below 2.5.
#'
#' @return A plain data frame with the 17 native input columns.
#' @export
lwbgt_input <- function(
    year,
    month,
    day,
    hour,
    minute,
    gmt_offset_hours,
    averaging_minutes,
    urban,
    latitude_deg_north,
    longitude_deg_east,
    solar_w_m2,
    pressure_hpa,
    air_temperature_c,
    relative_humidity_percent,
    wind_speed_m_s,
    wind_height_m,
    vertical_temperature_difference_c
) {
    fields <- list(
        year = year,
        month = month,
        day = day,
        hour = hour,
        minute = minute,
        gmt_offset_hours = gmt_offset_hours,
        averaging_minutes = averaging_minutes,
        urban = urban,
        latitude_deg_north = latitude_deg_north,
        longitude_deg_east = longitude_deg_east,
        solar_w_m2 = solar_w_m2,
        pressure_hpa = pressure_hpa,
        air_temperature_c = air_temperature_c,
        relative_humidity_percent = relative_humidity_percent,
        wind_speed_m_s = wind_speed_m_s,
        wind_height_m = wind_height_m,
        vertical_temperature_difference_c = vertical_temperature_difference_c
    )
    for (name in names(fields)) {
        value <- fields[[name]]
        if (!is.numeric(value) && !(name == "urban" && is.logical(value))) {
            stop(sprintf("%s must be numeric", name), call. = FALSE)
        }
    }
    fields <- .recycle_lwbgt_fields(fields)
    as.data.frame(fields, optional = TRUE, stringsAsFactors = FALSE)
}

#' Calculate outdoor wet bulb globe temperature
#'
#' Calculates one result per input row. Invalid rows are represented by a
#' nonzero status and `NA` numeric outputs; they do not prevent valid rows from
#' being calculated.
#'
#' @param input A data frame containing the columns produced by
#'   [lwbgt_input()]. Additional columns are ignored.
#' @param psychrometric Calculate psychrometric wet-bulb temperature, default
#'   `TRUE`. With `FALSE`, that column is `NA` and other results are unchanged.
#'
#' @return A plain data frame containing `status`, `status_message`,
#'   `estimated_wind_speed_m_s`, `globe_temperature_c`,
#'   `natural_wet_bulb_c`, `psychrometric_wet_bulb_c`, and `wbgt_c`.
#' @export
calculate <- function(input, psychrometric = TRUE) {
    if (!is.logical(psychrometric) || length(psychrometric) != 1L || is.na(psychrometric)) {
        stop("psychrometric must be TRUE or FALSE", call. = FALSE)
    }
    if (!is.data.frame(input)) {
        stop("input must be a data frame", call. = FALSE)
    }
    missing_names <- setdiff(.lwbgt_input_names, names(input))
    if (length(missing_names)) {
        stop(
            sprintf("input is missing required columns: %s", paste(missing_names, collapse = ", ")),
            call. = FALSE
        )
    }
    columns <- input[.lwbgt_input_names]
    for (name in names(columns)) {
        value <- columns[[name]]
        if (!is.numeric(value) && !(name == "urban" && is.logical(value))) {
            stop(sprintf("input column %s must be numeric", name), call. = FALSE)
        }
        columns[[name]] <- as.double(value)
    }
    result <- .Call(C_lwbgt_calculate, columns, psychrometric)
    class(result) <- "data.frame"
    attr(result, "row.names") <- attr(input, "row.names")
    result
}

#' Saturation vapour pressure
#'
#' Calls the native Liljegren saturation-pressure calculation over liquid
#' water or ice.
#'
#' @param temperature_k Numeric temperatures in kelvin.
#' @param phase Zero for liquid water or one for ice. Defaults to zero, matching
#'   the Python wrapper.
#'
#' @return A numeric vector in hectopascals. Missing inputs propagate to `NA`.
#'   Invalid non-missing inputs also return `NA` and produce one warning.
#' @export
esat <- function(temperature_k, phase = 0L) {
    if (!is.numeric(temperature_k)) {
        stop("temperature_k must be numeric", call. = FALSE)
    }
    if (!is.numeric(phase) && !is.logical(phase)) {
        stop("phase must be numeric", call. = FALSE)
    }
    fields <- .recycle_lwbgt_fields(list(temperature_k, phase))
    temperature_k <- as.double(fields[[1L]])
    phase <- as.double(fields[[2L]])
    missing <- (is.na(temperature_k) & !is.nan(temperature_k)) |
        (is.na(phase) & !is.nan(phase))
    invalid <- !missing & (
        !is.finite(temperature_k) |
        temperature_k <= 0 |
        !is.finite(phase) |
        phase != trunc(phase) |
        !phase %in% c(0, 1)
    )
    if (any(invalid)) {
        indices <- which(invalid)
        shown <- paste(indices[seq_len(min(length(indices), 10L))], collapse = ", ")
        suffix <- if (length(indices) > 10L) ", ..." else ""
        warning(sprintf("invalid temperature_k or phase at rows: %s%s", shown, suffix), call. = FALSE)
    }
    .Call(C_lwbgt_esat, temperature_k, phase)
}
