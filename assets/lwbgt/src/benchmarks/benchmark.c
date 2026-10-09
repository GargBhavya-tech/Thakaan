#define _GNU_SOURCE
#include "lwbgt.h"

#include <errno.h>
#include <inttypes.h>
#ifdef __linux__
#include <sched.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define FIELD_COUNT 19
#define LINE_CAPACITY 1024

typedef struct {
    int year, month, day, hour, minute, gmt, avg, urban;
    double lat, lon, solar, pressure, air, rh, wind, height, delta;
} Case;

typedef struct {
    size_t successes, failures;
    uint64_t checksum;
} Run;

static volatile uint64_t consumed_checksum;

static void fail(const char *message)
{
    fprintf(stderr, "benchmark-error: %s\n", message);
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
            *cursor = '\0'; start = cursor + 1;
        }
    }
}

static long integer(const char *text)
{
    char *end = NULL; errno = 0;
    long value = strtol(text, &end, 10);
    if (errno || end == text || *end) fail("invalid integer");
    return value;
}

static double number(const char *text)
{
    char *end = NULL; errno = 0;
    double value = strtod(text, &end);
    if (errno || end == text || *end) fail("invalid number");
    return value;
}

static Case *load_cases(const char *path, size_t *count_out)
{
    FILE *stream = fopen(path, "r");
    if (!stream) fail("cannot open cases");
    char line[LINE_CAPACITY]; size_t count = 0, capacity = 512;
    if (!fgets(line, sizeof(line), stream)) fail("cases are empty");
    Case *cases = calloc(capacity, sizeof(*cases));
    if (!cases) fail("allocation failed");
    while (fgets(line, sizeof(line), stream)) {
        line[strcspn(line, "\r\n")] = '\0';
        char *field[FIELD_COUNT];
        if (split(line, field) != FIELD_COUNT) fail("case width differs");
        if (count == capacity) {
            capacity *= 2;
            Case *grown = realloc(cases, capacity * sizeof(*grown));
            if (!grown) fail("allocation failed");
            cases = grown;
        }
        Case *item = &cases[count++];
        item->year = (int)integer(field[2]); item->month = (int)integer(field[3]);
        item->day = (int)integer(field[4]); item->hour = (int)integer(field[5]);
        item->minute = (int)integer(field[6]); item->gmt = (int)integer(field[7]);
        item->avg = (int)integer(field[8]); item->lat = number(field[9]);
        item->lon = number(field[10]); item->solar = number(field[11]);
        item->pressure = number(field[12]); item->air = number(field[13]);
        item->rh = number(field[14]); item->wind = number(field[15]);
        item->height = number(field[16]); item->delta = number(field[17]);
        item->urban = (int)integer(field[18]);
    }
    if (ferror(stream) || fclose(stream)) fail("cannot read cases");
    if (!count) fail("no benchmark cases");
    *count_out = count;
    return cases;
}

static int pin_cpu(void)
{
#ifdef __linux__
    cpu_set_t available;
    if (sched_getaffinity(0, sizeof(available), &available) != 0) return -1;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &available)) {
        cpu_set_t selected; CPU_ZERO(&selected); CPU_SET(cpu, &selected);
        return sched_setaffinity(0, sizeof(selected), &selected) == 0 ? cpu : -1;
    }
#endif
    return -1;
}

static double now(void)
{
    struct timespec value;
#ifdef CLOCK_MONOTONIC_RAW
    const clockid_t clock_id = CLOCK_MONOTONIC_RAW;
#else
    const clockid_t clock_id = CLOCK_MONOTONIC;
#endif
    if (clock_gettime(clock_id, &value)) fail("clock failed");
    return value.tv_sec + value.tv_nsec * 1e-9;
}

static Run exercise(const Case *cases, size_t count, long scale, int skip_psychrometric)
{
    Run result = {0, 0, 0};
    for (long repeat = 0; repeat < scale; ++repeat) {
        for (size_t index = 0; index < count; ++index) {
            const Case *item = &cases[index];
            float estimated = 0, globe = 0, natural = 0, psychrometric = -9999, wbgt = 0;
            int status = calc_wbgt(
                item->year, item->month, item->day, item->hour, item->minute,
                item->gmt, item->avg, item->lat, item->lon, item->solar,
                item->pressure, item->air, item->rh, item->wind, item->height,
                item->delta, item->urban, &estimated, &globe, &natural,
                skip_psychrometric ? NULL : &psychrometric, &wbgt
            );
            if (status == 0) ++result.successes;
            else if (status == -1) ++result.failures;
            else fail("unexpected calculation status");
            /* Consume every output bit, including infinities and NaNs. */
            const float outputs[] = {estimated, globe, natural, psychrometric, wbgt};
            for (size_t field = 0; field < 5; ++field) {
                uint32_t bits;
                memcpy(&bits, &outputs[field], sizeof(bits));
                result.checksum += bits;
            }
        }
    }
    consumed_checksum = result.checksum;
    return result;
}

int main(int argc, char **argv)
{
    if (argc != 3 && argc != 4) fail("expected CASES.csv SCALE [--skip-psychrometric]");
    int skip_psychrometric = argc == 4;
    if (skip_psychrometric && strcmp(argv[3], "--skip-psychrometric") != 0)
        fail("unknown option");
#ifdef LWBGT_REFERENCE
    if (skip_psychrometric) fail("the original kernel requires every output pointer");
#endif
    long scale = integer(argv[2]);
    if (scale < 1) fail("scale must be positive");
    size_t count = 0; Case *cases = load_cases(argv[1], &count);
    if ((uintmax_t)scale > SIZE_MAX / count) fail("call count overflow");
    int cpu = pin_cpu();
    (void)exercise(cases, count, 1, skip_psychrometric);
    double start = now();
    Run result = exercise(cases, count, scale, skip_psychrometric);
    double elapsed = now() - start;
    if (elapsed <= 0) fail("nonpositive elapsed time");
    size_t calls = count * (size_t)scale;
    printf("{\"cases\":%zu,\"scale\":%ld,\"calls\":%zu,"
           "\"successes\":%zu,\"failures\":%zu,\"cpu\":%d,"
           "\"compiler\":\"%s\",\"elapsed_seconds\":%.17g,"
           "\"rows_per_second\":%.17g,\"checksum\":%" PRIu64 "}\n",
           count, scale, calls, result.successes, result.failures, cpu,
           __VERSION__, elapsed, calls / elapsed, consumed_checksum);
    free(cases);
    return 0;
}
