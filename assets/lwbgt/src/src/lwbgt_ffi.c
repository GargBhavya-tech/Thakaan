#include "lwbgt.h"

#define LWBGT_ASSERT_OFFSET(type, field, expected) \
    _Static_assert(offsetof(type, field) == expected, "unexpected " #type " field offset")

_Static_assert(sizeof(lwbgt_input_v1) == 104, "unexpected lwbgt_input_v1 layout");
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, year, 0);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, month, 4);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, day, 8);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, hour, 12);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, minute, 16);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, gmt_offset_hours, 20);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, averaging_minutes, 24);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, urban, 28);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, latitude_deg_north, 32);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, longitude_deg_east, 40);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, solar_w_m2, 48);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, pressure_hpa, 56);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, air_temperature_c, 64);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, relative_humidity_percent, 72);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, wind_speed_m_s, 80);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, wind_height_m, 88);
LWBGT_ASSERT_OFFSET(lwbgt_input_v1, vertical_temperature_difference_c, 96);
_Static_assert(sizeof(lwbgt_output_v1) == 24, "unexpected lwbgt_output_v1 layout");
LWBGT_ASSERT_OFFSET(lwbgt_output_v1, status, 0);
LWBGT_ASSERT_OFFSET(lwbgt_output_v1, estimated_wind_speed_m_s, 4);
LWBGT_ASSERT_OFFSET(lwbgt_output_v1, globe_temperature_c, 8);
LWBGT_ASSERT_OFFSET(lwbgt_output_v1, natural_wet_bulb_c, 12);
LWBGT_ASSERT_OFFSET(lwbgt_output_v1, psychrometric_wet_bulb_c, 16);
LWBGT_ASSERT_OFFSET(lwbgt_output_v1, wbgt_c, 20);

int lwbgt_calc_batch_ex_v1(
    const lwbgt_input_v1 *inputs,
    lwbgt_output_v1 *outputs,
    size_t count,
    uint32_t flags
)
{
    size_t index;

    if (flags & ~(uint32_t)LWBGT_SKIP_PSYCHROMETRIC_WET_BULB)
        return LWBGT_BATCH_INVALID_ARGUMENT;
    if (count == 0) return LWBGT_BATCH_OK;
    if (inputs == NULL || outputs == NULL) return LWBGT_BATCH_INVALID_ARGUMENT;

    for (index = 0; index < count; ++index) {
        const lwbgt_input_v1 *input = &inputs[index];
        lwbgt_output_v1 *output = &outputs[index];

        output->estimated_wind_speed_m_s = (float)input->wind_speed_m_s;
        output->psychrometric_wet_bulb_c = -9999.0f;
        output->status = calc_wbgt(
            input->year,
            input->month,
            input->day,
            input->hour,
            input->minute,
            input->gmt_offset_hours,
            input->averaging_minutes,
            input->latitude_deg_north,
            input->longitude_deg_east,
            input->solar_w_m2,
            input->pressure_hpa,
            input->air_temperature_c,
            input->relative_humidity_percent,
            input->wind_speed_m_s,
            input->wind_height_m,
            input->vertical_temperature_difference_c,
            input->urban,
            &output->estimated_wind_speed_m_s,
            &output->globe_temperature_c,
            &output->natural_wet_bulb_c,
            flags & LWBGT_SKIP_PSYCHROMETRIC_WET_BULB ?
                NULL : &output->psychrometric_wet_bulb_c,
            &output->wbgt_c
        );
    }

    return LWBGT_BATCH_OK;
}

int lwbgt_calc_batch_v1(
    const lwbgt_input_v1 *inputs,
    lwbgt_output_v1 *outputs,
    size_t count
)
{
    return lwbgt_calc_batch_ex_v1(inputs, outputs, count, 0);
}
