#include "lwbgt.h"

#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FIELD_COUNT 19
#define LINE_CAPACITY 1024

static uint32_t float_bits(float value)
{
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static void fail(const char *message)
{
    fprintf(stderr, "probe-error: %s\n", message);
    exit(2);
}

static int split(char *line, char **fields)
{
    int count = 0;
    char *start = line;
    for (char *cursor = line;; ++cursor) {
        if (*cursor == ',' || *cursor == '\0') {
            if (count >= FIELD_COUNT) return count + 1;
            fields[count++] = start;
            if (*cursor == '\0') return count;
            *cursor = '\0';
            start = cursor + 1;
        }
    }
}

static long integer(const char *text)
{
    char *end = NULL;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno || end == text || *end) fail("invalid integer field");
    return value;
}

static double number(const char *text)
{
    char *end = NULL;
    errno = 0;
    double value = strtod(text, &end);
    if (errno || end == text || *end) fail("invalid numeric field");
    return value;
}

int main(int argc, char **argv)
{
    if (argc != 2) fail("expected CASES.csv");
    FILE *stream = fopen(argv[1], "r");
    if (!stream) fail("cannot open cases");

    char line[LINE_CAPACITY];
    if (!fgets(line, sizeof(line), stream)) fail("cases are empty");
    puts("case_id,status,estimated_wind,Tg,Tnwb,Tpsy,WBGT,esat");

    while (fgets(line, sizeof(line), stream)) {
        line[strcspn(line, "\r\n")] = '\0';
        char *field[FIELD_COUNT];
        if (split(line, field) != FIELD_COUNT) fail("case width differs");

        float estimated = -1234.5f;
        float globe = -1234.5f;
        float natural = -1234.5f;
        float psychrometric = -1234.5f;
        float wbgt = -1234.5f;
        double air = number(field[13]);
        int status = calc_wbgt(
            (int)integer(field[2]), (int)integer(field[3]), (int)integer(field[4]),
            (int)integer(field[5]), (int)integer(field[6]), (int)integer(field[7]),
            (int)integer(field[8]), number(field[9]), number(field[10]),
            number(field[11]), number(field[12]), air, number(field[14]),
            number(field[15]), number(field[16]), number(field[17]),
            (int)integer(field[18]), &estimated, &globe, &natural,
            &psychrometric, &wbgt
        );
        float saturation = esat(air + 273.15, 0);
        printf(
            "%s,%d,%08" PRIx32 ",%08" PRIx32 ",%08" PRIx32
            ",%08" PRIx32 ",%08" PRIx32 ",%08" PRIx32 "\n",
            field[0], status, float_bits(estimated), float_bits(globe),
            float_bits(natural), float_bits(psychrometric), float_bits(wbgt),
            float_bits(saturation)
        );
    }
    if (ferror(stream) || fclose(stream)) fail("cannot read cases");
    return 0;
}
