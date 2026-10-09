#include "lwbgt.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FIELD_COUNT 19
#define LINE_CAPACITY 1024
#define MAX_CASES 1024

static void fail(const char *message)
{
    fprintf(stderr, "batch-test-error: %s\n", message);
    exit(2);
}

static int split(char *line, char **fields)
{
    int count = 0;
    char *start = line;
    char *cursor;

    for (cursor = line;; ++cursor) {
        if (*cursor == ',' || *cursor == '\0') {
            if (count >= FIELD_COUNT) return count + 1;
            fields[count++] = start;
            if (*cursor == '\0') return count;
            *cursor = '\0';
            start = cursor + 1;
        }
    }
}

static int32_t integer(const char *text)
{
    char *end = NULL;
    long value;

    errno = 0;
    value = strtol(text, &end, 10);
    if (errno || end == text || *end) fail("invalid integer field");
    return (int32_t)value;
}

static double number(const char *text)
{
    char *end = NULL;
    double value;

    errno = 0;
    value = strtod(text, &end);
    if (errno || end == text || *end) fail("invalid numeric field");
    return value;
}

static lwbgt_input_v1 parse_input(char **field)
{
    lwbgt_input_v1 input;

    input.year = integer(field[2]);
    input.month = integer(field[3]);
    input.day = integer(field[4]);
    input.hour = integer(field[5]);
    input.minute = integer(field[6]);
    input.gmt_offset_hours = integer(field[7]);
    input.averaging_minutes = integer(field[8]);
    input.urban = integer(field[18]);
    input.latitude_deg_north = number(field[9]);
    input.longitude_deg_east = number(field[10]);
    input.solar_w_m2 = number(field[11]);
    input.pressure_hpa = number(field[12]);
    input.air_temperature_c = number(field[13]);
    input.relative_humidity_percent = number(field[14]);
    input.wind_speed_m_s = number(field[15]);
    input.wind_height_m = number(field[16]);
    input.vertical_temperature_difference_c = number(field[17]);
    return input;
}

static lwbgt_output_v1 scalar(const lwbgt_input_v1 *input, int psychrometric)
{
    lwbgt_output_v1 output;

    output.estimated_wind_speed_m_s = (float)input->wind_speed_m_s;
    output.psychrometric_wet_bulb_c = -9999.0f;
    output.status = calc_wbgt(
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
        &output.estimated_wind_speed_m_s,
        &output.globe_temperature_c,
        &output.natural_wet_bulb_c,
        psychrometric ? &output.psychrometric_wet_bulb_c : NULL,
        &output.wbgt_c
    );
    return output;
}

static int same_float(float left, float right)
{
    uint32_t left_bits;
    uint32_t right_bits;

    memcpy(&left_bits, &left, sizeof(left_bits));
    memcpy(&right_bits, &right, sizeof(right_bits));
    return left_bits == right_bits;
}

static int same_output(const lwbgt_output_v1 *left, const lwbgt_output_v1 *right)
{
    return left->status == right->status &&
        same_float(left->estimated_wind_speed_m_s, right->estimated_wind_speed_m_s) &&
        same_float(left->globe_temperature_c, right->globe_temperature_c) &&
        same_float(left->natural_wet_bulb_c, right->natural_wet_bulb_c) &&
        same_float(left->psychrometric_wet_bulb_c, right->psychrometric_wet_bulb_c) &&
        same_float(left->wbgt_c, right->wbgt_c);
}

static void check_argument_contract(const lwbgt_input_v1 *input)
{
    lwbgt_output_v1 output;
    lwbgt_output_v1 unchanged;

    if (lwbgt_calc_batch_v1(NULL, NULL, 0) != LWBGT_BATCH_OK)
        fail("zero-count null call failed");

    memset(&output, 0xa5, sizeof(output));
    unchanged = output;
    if (lwbgt_calc_batch_v1(NULL, &output, 1) != LWBGT_BATCH_INVALID_ARGUMENT)
        fail("null input was accepted");
    if (memcmp(&output, &unchanged, sizeof(output)) != 0)
        fail("invalid call modified output");
    if (lwbgt_calc_batch_v1(input, NULL, 1) != LWBGT_BATCH_INVALID_ARGUMENT)
        fail("null output was accepted");
    if (lwbgt_calc_batch_ex_v1(NULL, NULL, 0, 0) != LWBGT_BATCH_OK ||
        lwbgt_calc_batch_ex_v1(NULL, NULL, 0,
            LWBGT_SKIP_PSYCHROMETRIC_WET_BULB) != LWBGT_BATCH_OK)
        fail("extended zero-count call failed");
    if (lwbgt_calc_batch_ex_v1(NULL, &output, 1, 0) != LWBGT_BATCH_INVALID_ARGUMENT ||
        lwbgt_calc_batch_ex_v1(input, NULL, 1, 0) != LWBGT_BATCH_INVALID_ARGUMENT)
        fail("extended null argument was accepted");
    if (lwbgt_calc_batch_ex_v1(input, &output, 1, 2u) != LWBGT_BATCH_INVALID_ARGUMENT ||
        lwbgt_calc_batch_ex_v1(NULL, NULL, 0, UINT32_MAX) != LWBGT_BATCH_INVALID_ARGUMENT)
        fail("unknown option was accepted");
    if (memcmp(&output, &unchanged, sizeof(output)) != 0)
        fail("invalid extended call modified output");
}

static void check_invalid_solar_failure(void)
{
    static const struct {
        int year, month, day;
        double lat, lon;
    } cases[] = {
        {1949, 1, 1, 0.0, 0.0}, {2050, 1, 1, 0.0, 0.0},
        {INT32_MIN, 1, 1, 0.0, 0.0}, {INT32_MAX, 1, 1, 0.0, 0.0},
        {2024, 1, 1, -90.01, 0.0}, {2024, 1, 1, 90.01, 0.0},
        {2024, 1, 1, 0.0, -180.01}, {2024, 1, 1, 0.0, 180.01},
        {2024, 1, 1, -90.00001, 0.0}, {2024, 1, 1, 90.00001, 0.0},
        {2024, 1, 1, 0.0, -180.00002}, {2024, 1, 1, 0.0, 180.00002},
        {2024, -1, 1, 0.0, 0.0}, {2024, 13, 1, 0.0, 0.0},
        {2024, 1, -2, 0.0, 0.0}, {2024, 1, 34, 0.0, 0.0},
        {2024, 0, -2, 0.0, 0.0}, {2024, 0, 369, 0.0, 0.0},
    };
    size_t index;

    for (index = 0; index < sizeof(cases) / sizeof(cases[0]); ++index) {
        float estimated_wind = 1.0f;
        float globe = 2.0f;
        float natural_wet_bulb = 3.0f;
        float psychrometric_wet_bulb = 4.0f;
        float wbgt = 5.0f;
        lwbgt_input_v1 input = {0};
        lwbgt_output_v1 output;
        int status = calc_wbgt(
            cases[index].year, cases[index].month, cases[index].day, 12, 0, 0, 60,
            cases[index].lat, cases[index].lon, 500.0, 1013.0,
            25.0, 50.0, 2.0, 2.0, 0.0, 0, &estimated_wind, &globe,
            &natural_wet_bulb, &psychrometric_wet_bulb, &wbgt
        );
        if (status != -1 ||
            !same_float(estimated_wind, -9999.0f) ||
            !same_float(globe, -9999.0f) ||
            !same_float(natural_wet_bulb, -9999.0f) ||
            !same_float(psychrometric_wet_bulb, -9999.0f) ||
            !same_float(wbgt, -9999.0f))
            fail("invalid solar input did not initialize failure outputs");
        input.year = cases[index].year;
        input.month = cases[index].month;
        input.day = cases[index].day;
        input.hour = 12;
        input.latitude_deg_north = cases[index].lat;
        input.longitude_deg_east = cases[index].lon;
        input.wind_height_m = 2.0;
        memset(&output, 0xa5, sizeof(output));
        if (lwbgt_calc_batch_v1(&input, &output, 1) != LWBGT_BATCH_OK ||
            output.status != -1 ||
            !same_float(output.estimated_wind_speed_m_s, -9999.0f) ||
            !same_float(output.globe_temperature_c, -9999.0f) ||
            !same_float(output.natural_wet_bulb_c, -9999.0f) ||
            !same_float(output.psychrometric_wet_bulb_c, -9999.0f) ||
            !same_float(output.wbgt_c, -9999.0f))
            fail("invalid solar batch output is not deterministic");
    }
}

static void check_scalar_2m_output(void)
{
    float estimated_wind = -1234.5f;
    float globe, natural_wet_bulb, psychrometric_wet_bulb, wbgt;

    if (calc_wbgt(
            2024, 3, 21, 12, 0, 0, 0, 0.0, 0.0, 500.0, 1013.0,
            25.0, 50.0, 2.0, 2.0, 0.0, 0, &estimated_wind, &globe,
            &natural_wet_bulb, &psychrometric_wet_bulb, &wbgt
        ) != 0 || !same_float(estimated_wind, 2.0f))
        fail("scalar 2 m wind output was not assigned");
}

static void check_supported_year_bounds(void)
{
    static const int years[] = {1950, 2049};
    size_t index;

    for (index = 0; index < sizeof(years) / sizeof(years[0]); ++index) {
        float estimated_wind, globe, natural_wet_bulb, psychrometric_wet_bulb, wbgt;
        int status = calc_wbgt(
            years[index], 3, 1, 12, 0, 0, 0, 0.0, 0.0, 500.0, 1013.0,
            25.0, 50.0, 2.0, 2.0, 0.0, 0, &estimated_wind, &globe,
            &natural_wet_bulb, &psychrometric_wet_bulb, &wbgt
        );
        if (status != 0 || !same_float(estimated_wind, 2.0f))
            fail("supported year boundary was not calculated");
    }
}

static void check_batch(const lwbgt_input_v1 *inputs,
                        const lwbgt_output_v1 *expected,
                        lwbgt_output_v1 *actual, char identifiers[][32],
                        size_t count)
{
    size_t index;

    memset(actual, 0xa5, count * sizeof(*actual));
    if (lwbgt_calc_batch_v1(inputs, actual, count) != LWBGT_BATCH_OK)
        fail("batch call failed");

    for (index = 0; index < count; ++index) {
        if (!same_output(&expected[index], &actual[index])) {
            fprintf(stderr, "batch-test-error: mismatch for %s\n", identifiers[index]);
            fail("scalar/batch output differs");
        }
        if ((float)inputs[index].wind_height_m == 2.0 &&
            !same_float(actual[index].estimated_wind_speed_m_s,
                        (float)inputs[index].wind_speed_m_s))
            fail("2 m wind output is not deterministic");
    }
    if (lwbgt_calc_batch_ex_v1(inputs, actual, count, 0) != LWBGT_BATCH_OK)
        fail("extended full-output call failed");
    for (index = 0; index < count; ++index)
        if (!same_output(&expected[index], &actual[index]))
            fail("extended full-output call differs from scalar");

    memset(actual, 0xa5, count * sizeof(*actual));
    if (lwbgt_calc_batch_ex_v1(inputs, actual, count,
            LWBGT_SKIP_PSYCHROMETRIC_WET_BULB) != LWBGT_BATCH_OK)
        fail("psychrometric opt-out failed");
    for (index = 0; index < count; ++index) {
        lwbgt_output_v1 wanted = expected[index];
        lwbgt_output_v1 skipped_scalar = scalar(&inputs[index], 0);
        wanted.psychrometric_wet_bulb_c = -9999.0f;
        if (!same_output(&wanted, &actual[index]) ||
            !same_output(&wanted, &skipped_scalar)) {
            fprintf(stderr, "batch-test-error: opt-out mismatch for %s\n", identifiers[index]);
            fail("psychrometric opt-out changed another output");
        }
    }
}

int main(int argc, char **argv)
{
    lwbgt_input_v1 inputs[MAX_CASES];
    lwbgt_output_v1 expected[MAX_CASES];
    lwbgt_output_v1 actual[MAX_CASES];
    char identifiers[MAX_CASES][32];
    char line[LINE_CAPACITY];
    size_t count = 0;
    size_t total = 0;
    FILE *stream;

    if (argc != 2) fail("expected CASES.csv");
    stream = fopen(argv[1], "r");
    if (stream == NULL) fail("cannot open cases");
    if (fgets(line, sizeof(line), stream) == NULL) fail("cases are empty");

    check_invalid_solar_failure();
    check_scalar_2m_output();
    check_supported_year_bounds();

    while (fgets(line, sizeof(line), stream) != NULL) {
        char *field[FIELD_COUNT];

        if (count == MAX_CASES) {
            check_batch(inputs, expected, actual, identifiers, count);
            total += count;
            count = 0;
        }
        line[strcspn(line, "\r\n")] = '\0';
        if (split(line, field) != FIELD_COUNT) fail("case width differs");
        if (strlen(field[0]) >= sizeof(identifiers[count])) fail("case id is too long");
        strcpy(identifiers[count], field[0]);
        inputs[count] = parse_input(field);
        expected[count] = scalar(&inputs[count], 1);
        if (total == 0 && count == 0) check_argument_contract(&inputs[0]);
        ++count;
    }
    if (ferror(stream) || fclose(stream)) fail("cannot read cases");
    if (total == 0 && count == 0) fail("no cases generated");

    if (count != 0) check_batch(inputs, expected, actual, identifiers, count);
    total += count;

    printf("batch-equivalence: %zu bit-identical cases\n", total);
    return 0;
}
