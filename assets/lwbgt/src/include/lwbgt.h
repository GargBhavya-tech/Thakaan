#ifndef LWBGT_H
#define LWBGT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LWBGT_VERSION_MAJOR 1
#define LWBGT_VERSION_MINOR 2
#define LWBGT_VERSION_PATCH 0
#define LWBGT_FFI_ABI_VERSION 1

typedef struct lwbgt_input_v1 {
    int32_t year;
    int32_t month;
    int32_t day;
    int32_t hour;
    int32_t minute;
    int32_t gmt_offset_hours;
    int32_t averaging_minutes;
    int32_t urban;
    double latitude_deg_north;
    double longitude_deg_east;
    double solar_w_m2;
    double pressure_hpa;
    double air_temperature_c;
    double relative_humidity_percent;
    double wind_speed_m_s;
    double wind_height_m;
    /* Upper minus lower, deg C; its sign is used only for rural nighttime wind
     * scaling below 2.5 m/s. */
    double vertical_temperature_difference_c;
} lwbgt_input_v1;

typedef struct lwbgt_output_v1 {
    int32_t status;
    float estimated_wind_speed_m_s;
    float globe_temperature_c;
    float natural_wet_bulb_c;
    float psychrometric_wet_bulb_c;
    float wbgt_c;
} lwbgt_output_v1;

enum {
    LWBGT_BATCH_OK = 0,
    LWBGT_BATCH_INVALID_ARGUMENT = 1
};

enum {
    LWBGT_SKIP_PSYCHROMETRIC_WET_BULB = 1u
};

int calc_wbgt(
    int year,
    int month,
    int day,
    int hour,
    int minute,
    int gmt,
    int averaging_minutes,
    double latitude_deg_north,
    double longitude_deg_east,
    double solar_w_m2,
    double pressure_hpa,
    double air_temperature_c,
    double relative_humidity_percent,
    double wind_speed_m_s,
    double wind_height_m,
    /* Upper minus lower, deg C; its sign is used only for rural nighttime wind
     * scaling below 2.5 m/s. */
    double vertical_temperature_difference_c,
    int urban,
    float *estimated_wind_speed_m_s,
    float *globe_temperature_c,
    float *natural_wet_bulb_c,
    /* NULL skips the psychrometric wet-bulb calculation. */
    float *psychrometric_wet_bulb_c,
    float *wbgt_c
);

float esat(double temperature_k, int phase);

/* Solver status is written per output; the return value reports call validity. */
int lwbgt_calc_batch_v1(
    const lwbgt_input_v1 *inputs,
    lwbgt_output_v1 *outputs,
    size_t count
);

/* flags == 0 calculates every output. Skipped psychrometric outputs are -9999.
 * Unknown flags return LWBGT_BATCH_INVALID_ARGUMENT without modifying outputs. */
int lwbgt_calc_batch_ex_v1(
    const lwbgt_input_v1 *inputs,
    lwbgt_output_v1 *outputs,
    size_t count,
    uint32_t flags
);

#ifdef __cplusplus
}
#endif

#endif
