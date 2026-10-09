/* SPDX-License-Identifier: Apache-2.0 */

#include "lwbgt.h"

#include <limits.h>

#include <R.h>
#include <R_ext/Arith.h>
#include <R_ext/Rdynload.h>
#include <R_ext/Utils.h>
#include <R_ext/Visibility.h>
#include <Rinternals.h>

enum {
    YEAR,
    MONTH,
    DAY,
    HOUR,
    MINUTE,
    GMT_OFFSET_HOURS,
    AVERAGING_MINUTES,
    URBAN,
    LATITUDE_DEG_NORTH,
    LONGITUDE_DEG_EAST,
    SOLAR_W_M2,
    PRESSURE_HPA,
    AIR_TEMPERATURE_C,
    RELATIVE_HUMIDITY_PERCENT,
    WIND_SPEED_M_S,
    WIND_HEIGHT_M,
    VERTICAL_TEMPERATURE_DIFFERENCE_C,
    INPUT_FIELD_COUNT
};

enum {
    STATUS_OK = 0,
    STATUS_SOLVER_FAILURE = -1,
    STATUS_MISSING_INPUT = 1,
    STATUS_INVALID_INPUT = 2,
    STATUS_INVALID_OUTPUT = 3
};

enum { OUTPUT_FIELD_COUNT = 7 };

static const char *input_names[INPUT_FIELD_COUNT] = {
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
};

static const char *missing_messages[INPUT_FIELD_COUNT] = {
    "missing input: year",
    "missing input: month",
    "missing input: day",
    "missing input: hour",
    "missing input: minute",
    "missing input: gmt_offset_hours",
    "missing input: averaging_minutes",
    "missing input: urban",
    "missing input: latitude_deg_north",
    "missing input: longitude_deg_east",
    "missing input: solar_w_m2",
    "missing input: pressure_hpa",
    "missing input: air_temperature_c",
    "missing input: relative_humidity_percent",
    "missing input: wind_speed_m_s",
    "missing input: wind_height_m",
    "missing input: vertical_temperature_difference_c"
};

static int integer_value(double value)
{
    if (!R_FINITE(value)) return 0;
    if (value < (double)INT_MIN || value > (double)INT_MAX) return 0;
    return value == (double)((int)value);
}

static int leap_year(int year)
{
    return ((year % 4) == 0 && (year % 100) != 0) || (year % 400) == 0;
}

static int valid_calendar_day(int year, int month, int day)
{
    static const int month_days[12] = {
        31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
    };
    int maximum;

    if (month == 0)
        return day >= 1 && day <= 365 + leap_year(year);
    maximum = month_days[month - 1];
    if (month == 2 && leap_year(year)) maximum = 29;
    return day >= 1 && day <= maximum;
}

static int validate_row(double *columns[INPUT_FIELD_COUNT], R_xlen_t index,
                        const char **message)
{
    int field;
    int year;
    int month;
    int day;
    double value;

    for (field = 0; field < INPUT_FIELD_COUNT; ++field) {
        if (ISNA(columns[field][index])) {
            *message = missing_messages[field];
            return STATUS_MISSING_INPUT;
        }
    }

    value = columns[YEAR][index];
    if (!integer_value(value) || value < 1950.0 || value > 2049.0) {
        *message = "invalid input: year must be an integer from 1950 to 2049";
        return STATUS_INVALID_INPUT;
    }
    year = (int)value;

    value = columns[MONTH][index];
    if (!integer_value(value) || value < 0.0 || value > 12.0) {
        *message = "invalid input: month must be an integer from 0 to 12";
        return STATUS_INVALID_INPUT;
    }
    month = (int)value;

    value = columns[DAY][index];
    if (!integer_value(value)) {
        *message = "invalid input: day must be an integer";
        return STATUS_INVALID_INPUT;
    }
    day = (int)value;
    if (!valid_calendar_day(year, month, day)) {
        *message = "invalid input: day is outside the selected year and month";
        return STATUS_INVALID_INPUT;
    }

    value = columns[HOUR][index];
    if (!integer_value(value) || value < 0.0 || value > 23.0) {
        *message = "invalid input: hour must be an integer from 0 to 23";
        return STATUS_INVALID_INPUT;
    }
    value = columns[MINUTE][index];
    if (!integer_value(value) || value < 0.0 || value > 59.0) {
        *message = "invalid input: minute must be an integer from 0 to 59";
        return STATUS_INVALID_INPUT;
    }
    value = columns[GMT_OFFSET_HOURS][index];
    if (!integer_value(value) || value < -12.0 || value > 14.0) {
        *message = "invalid input: gmt_offset_hours must be an integer from -12 to 14";
        return STATUS_INVALID_INPUT;
    }
    value = columns[AVERAGING_MINUTES][index];
    if (!integer_value(value) || value < 0.0 || value > 1440.0) {
        *message = "invalid input: averaging_minutes must be an integer from 0 to 1440";
        return STATUS_INVALID_INPUT;
    }
    value = columns[URBAN][index];
    if (!integer_value(value) || (value != 0.0 && value != 1.0)) {
        *message = "invalid input: urban must be zero or one";
        return STATUS_INVALID_INPUT;
    }

    value = columns[LATITUDE_DEG_NORTH][index];
    if (!R_FINITE(value) || value < -90.0 || value > 90.0) {
        *message = "invalid input: latitude_deg_north must be from -90 to 90";
        return STATUS_INVALID_INPUT;
    }
    value = columns[LONGITUDE_DEG_EAST][index];
    if (!R_FINITE(value) || value < -180.0 || value > 180.0) {
        *message = "invalid input: longitude_deg_east must be from -180 to 180";
        return STATUS_INVALID_INPUT;
    }
    value = columns[SOLAR_W_M2][index];
    if (!R_FINITE(value) || value < 0.0) {
        *message = "invalid input: solar_w_m2 must be finite and nonnegative";
        return STATUS_INVALID_INPUT;
    }
    value = columns[PRESSURE_HPA][index];
    if (!R_FINITE(value) || value <= 0.0) {
        *message = "invalid input: pressure_hpa must be finite and positive";
        return STATUS_INVALID_INPUT;
    }
    value = columns[AIR_TEMPERATURE_C][index];
    if (!R_FINITE(value) || value <= -273.15) {
        *message = "invalid input: air_temperature_c must be above absolute zero";
        return STATUS_INVALID_INPUT;
    }
    value = columns[RELATIVE_HUMIDITY_PERCENT][index];
    if (!R_FINITE(value) || value < 0.0 || value > 100.0) {
        *message = "invalid input: relative_humidity_percent must be from 0 to 100";
        return STATUS_INVALID_INPUT;
    }
    value = columns[WIND_SPEED_M_S][index];
    if (!R_FINITE(value) || value < 0.0) {
        *message = "invalid input: wind_speed_m_s must be finite and nonnegative";
        return STATUS_INVALID_INPUT;
    }
    value = columns[WIND_HEIGHT_M][index];
    if (!R_FINITE(value) || value <= 0.0) {
        *message = "invalid input: wind_height_m must be finite and positive";
        return STATUS_INVALID_INPUT;
    }
    value = columns[VERTICAL_TEMPERATURE_DIFFERENCE_C][index];
    if (!R_FINITE(value)) {
        *message = "invalid input: vertical_temperature_difference_c must be finite";
        return STATUS_INVALID_INPUT;
    }

    *message = "ok";
    return STATUS_OK;
}

static SEXP lwbgt_r_calculate(SEXP input, SEXP psychrometric)
{
    static const char *output_names[OUTPUT_FIELD_COUNT] = {
        "status",
        "status_message",
        "estimated_wind_speed_m_s",
        "globe_temperature_c",
        "natural_wet_bulb_c",
        "psychrometric_wet_bulb_c",
        "wbgt_c"
    };
    double *columns[INPUT_FIELD_COUNT];
    SEXP result;
    SEXP names;
    SEXP status_column;
    SEXP message_column;
    SEXP estimated_wind_column;
    SEXP globe_column;
    SEXP natural_wet_bulb_column;
    SEXP psychrometric_wet_bulb_column;
    SEXP wbgt_column;
    R_xlen_t count;
    R_xlen_t index;
    int field;
    int include_psychrometric;

    if (TYPEOF(psychrometric) != LGLSXP || XLENGTH(psychrometric) != 1 ||
        LOGICAL(psychrometric)[0] == NA_LOGICAL)
        error("psychrometric must be TRUE or FALSE");
    include_psychrometric = LOGICAL(psychrometric)[0];
    if (TYPEOF(input) != VECSXP || XLENGTH(input) != INPUT_FIELD_COUNT)
        error("native calculation requires exactly 17 input columns");
    count = XLENGTH(VECTOR_ELT(input, 0));
    for (field = 0; field < INPUT_FIELD_COUNT; ++field) {
        SEXP column = VECTOR_ELT(input, field);
        if (TYPEOF(column) != REALSXP)
            error("native input column %s must be numeric", input_names[field]);
        if (XLENGTH(column) != count)
            error("native input columns must have equal lengths");
        columns[field] = REAL(column);
    }

    PROTECT(result = allocVector(VECSXP, OUTPUT_FIELD_COUNT));
    PROTECT(names = allocVector(STRSXP, OUTPUT_FIELD_COUNT));
    PROTECT(status_column = allocVector(INTSXP, count));
    PROTECT(message_column = allocVector(STRSXP, count));
    PROTECT(estimated_wind_column = allocVector(REALSXP, count));
    PROTECT(globe_column = allocVector(REALSXP, count));
    PROTECT(natural_wet_bulb_column = allocVector(REALSXP, count));
    PROTECT(psychrometric_wet_bulb_column = allocVector(REALSXP, count));
    PROTECT(wbgt_column = allocVector(REALSXP, count));

    SET_VECTOR_ELT(result, 0, status_column);
    SET_VECTOR_ELT(result, 1, message_column);
    SET_VECTOR_ELT(result, 2, estimated_wind_column);
    SET_VECTOR_ELT(result, 3, globe_column);
    SET_VECTOR_ELT(result, 4, natural_wet_bulb_column);
    SET_VECTOR_ELT(result, 5, psychrometric_wet_bulb_column);
    SET_VECTOR_ELT(result, 6, wbgt_column);
    for (field = 0; field < OUTPUT_FIELD_COUNT; ++field)
        SET_STRING_ELT(names, field, mkChar(output_names[field]));
    setAttrib(result, R_NamesSymbol, names);

    for (index = 0; index < count; ++index) {
        const char *message;
        int status;
        int native_status;
        float estimated_wind;
        float globe;
        float natural_wet_bulb;
        float psychrometric_wet_bulb;
        float wbgt;

        if ((index % 1024) == 0) R_CheckUserInterrupt();
        REAL(estimated_wind_column)[index] = NA_REAL;
        REAL(globe_column)[index] = NA_REAL;
        REAL(natural_wet_bulb_column)[index] = NA_REAL;
        REAL(psychrometric_wet_bulb_column)[index] = NA_REAL;
        REAL(wbgt_column)[index] = NA_REAL;

        status = validate_row(columns, index, &message);
        if (status != STATUS_OK) {
            INTEGER(status_column)[index] = status;
            SET_STRING_ELT(message_column, index, mkChar(message));
            continue;
        }

        estimated_wind = (float)columns[WIND_SPEED_M_S][index];
        globe = natural_wet_bulb = psychrometric_wet_bulb = wbgt = -9999.0f;
        native_status = calc_wbgt(
            (int)columns[YEAR][index],
            (int)columns[MONTH][index],
            (int)columns[DAY][index],
            (int)columns[HOUR][index],
            (int)columns[MINUTE][index],
            (int)columns[GMT_OFFSET_HOURS][index],
            (int)columns[AVERAGING_MINUTES][index],
            columns[LATITUDE_DEG_NORTH][index],
            columns[LONGITUDE_DEG_EAST][index],
            columns[SOLAR_W_M2][index],
            columns[PRESSURE_HPA][index],
            columns[AIR_TEMPERATURE_C][index],
            columns[RELATIVE_HUMIDITY_PERCENT][index],
            columns[WIND_SPEED_M_S][index],
            columns[WIND_HEIGHT_M][index],
            columns[VERTICAL_TEMPERATURE_DIFFERENCE_C][index],
            (int)columns[URBAN][index],
            &estimated_wind,
            &globe,
            &natural_wet_bulb,
            include_psychrometric ? &psychrometric_wet_bulb : NULL,
            &wbgt
        );

        if (native_status == STATUS_SOLVER_FAILURE) {
            INTEGER(status_column)[index] = STATUS_SOLVER_FAILURE;
            SET_STRING_ELT(message_column, index, mkChar("solver did not converge"));
            continue;
        }
        if (native_status != STATUS_OK ||
            !R_FINITE((double)estimated_wind) ||
            !R_FINITE((double)globe) ||
            !R_FINITE((double)natural_wet_bulb) ||
            (include_psychrometric && !R_FINITE((double)psychrometric_wet_bulb)) ||
            !R_FINITE((double)wbgt)) {
            INTEGER(status_column)[index] = STATUS_INVALID_OUTPUT;
            SET_STRING_ELT(message_column, index, mkChar("native calculation returned an invalid result"));
            continue;
        }

        INTEGER(status_column)[index] = STATUS_OK;
        SET_STRING_ELT(message_column, index, mkChar("ok"));
        REAL(estimated_wind_column)[index] = estimated_wind;
        REAL(globe_column)[index] = globe;
        REAL(natural_wet_bulb_column)[index] = natural_wet_bulb;
        if (include_psychrometric)
            REAL(psychrometric_wet_bulb_column)[index] = psychrometric_wet_bulb;
        REAL(wbgt_column)[index] = wbgt;
    }

    UNPROTECT(9);
    return result;
}

static SEXP lwbgt_r_esat(SEXP temperature_k, SEXP phase)
{
    SEXP result;
    R_xlen_t count;
    R_xlen_t index;

    if (TYPEOF(temperature_k) != REALSXP || TYPEOF(phase) != REALSXP)
        error("native esat inputs must be numeric");
    count = XLENGTH(temperature_k);
    if (XLENGTH(phase) != count)
        error("native esat inputs must have equal lengths");
    PROTECT(result = allocVector(REALSXP, count));
    for (index = 0; index < count; ++index) {
        double temperature = REAL(temperature_k)[index];
        double phase_value = REAL(phase)[index];
        if (ISNA(temperature) || ISNA(phase_value) ||
            !R_FINITE(temperature) || temperature <= 0.0 ||
            !integer_value(phase_value) ||
            (phase_value != 0.0 && phase_value != 1.0)) {
            REAL(result)[index] = NA_REAL;
        } else {
            REAL(result)[index] = esat(temperature, (int)phase_value);
        }
    }
    UNPROTECT(1);
    return result;
}

static const R_CallMethodDef call_methods[] = {
    {"lwbgt_calculate", (DL_FUNC)&lwbgt_r_calculate, 2},
    {"lwbgt_esat", (DL_FUNC)&lwbgt_r_esat, 2},
    {NULL, NULL, 0}
};

void attribute_visible R_init_lwbgt(DllInfo *dll)
{
    R_registerRoutines(dll, NULL, call_methods, NULL, NULL);
    R_useDynamicSymbols(dll, FALSE);
    R_forceSymbols(dll, TRUE);
}
