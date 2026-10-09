/* Internal numerical diagnostics; these helpers are not public API. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#ifdef LWBGT_REFERENCE
/* The retained K&R float parameters undergo default argument promotion. */
int calc_solar_parameters(int, int, double, double, double, float *, float *, float *);
int stab_srdt(int, double, double, double);
#else
/* Compile the kernel here to probe helpers with internal linkage. */
#include "../src/wbgt.c"
#endif

static unsigned int bits(float value)
{
    uint32_t result;
    memcpy(&result, &value, sizeof(result));
    return (unsigned int)result;
}

static float solar_at(double day, float latitude, float longitude, float input,
                      const char *label, size_t *count)
{
    float solar = input, cza, direct;
    int status = calc_solar_parameters(2024, 3, day, latitude, longitude,
                                       &solar, &cza, &direct);
    if (status != 0) return NAN;
    if (label != NULL)
        printf("%s-%05zu,%d,%08x,%08x,%08x\n", label, ++*count, status,
               bits(solar), bits(cza), bits(direct));
    return label == NULL ? cza : solar;
}

int main(void)
{
    static const float speeds[] = {0.13f, 2.0f, 2.5f, 3.0f, 5.0f, 6.0f};
    static const float radiation[] = {175.0f, 675.0f, 925.0f};
    /* Match CZA_MIN's double literal when comparing the rounded cosine. */
    static const double targets[] = {0.0, 0.00873};
    static const float latitudes[] = {-80.0f, -45.0f, 0.0f, 45.0f, 80.0f};
    size_t count = 0, i, j;
    int side, solar_side, daytime, inversion;

    puts("case_id,status,solar_or_speed,cza_or_radiation,direct_or_delta");
    /* Probe the stability table with the actual adjacent float inputs;
       the end-to-end suite also exercises solar adjustment beforehand. */
    for (i = 0; i < sizeof(speeds) / sizeof(speeds[0]); ++i)
        for (j = 0; j < sizeof(radiation) / sizeof(radiation[0]); ++j)
            for (side = -1; side <= 1; ++side)
                for (solar_side = -1; solar_side <= 1; ++solar_side)
                    for (daytime = 0; daytime <= 1; ++daytime)
                        for (inversion = -1; inversion <= 1; ++inversion) {
                            float speed = side == 0 ? speeds[i] :
                                nextafterf(speeds[i], side < 0 ? -INFINITY : INFINITY);
                            float solar = solar_side == 0 ? radiation[j] :
                                nextafterf(radiation[j], solar_side < 0 ? -INFINITY : INFINITY);
                            int stability = stab_srdt(daytime, speed, solar, (float)inversion);
                            if (stability < 1 || stability > 6) return 2;
                            printf("stability-%05zu,%d,%08x,%08x,%08x\n", ++count,
                                   stability, bits(speed), bits(solar), bits((float)inversion));
                        }

    /* Locate adjacent longitude floats straddling each cosine threshold,
       using the linked kernel itself rather than an approximate ephemeris. */
    for (i = 0; i < sizeof(targets) / sizeof(targets[0]); ++i) {
        float lower = -30.0f, upper = 30.0f;
        while (nextafterf(lower, INFINITY) < upper) {
            float middle = (float)(((double)lower + upper) * 0.5);
            float cza = solar_at(20.25, 0.0f, middle, 1000.0f, NULL, &count);
            if (!isfinite(cza)) return 2;
            if (cza < targets[i]) lower = middle;
            else upper = middle;
        }
        if (!(solar_at(20.25, 0.0f, lower, 1000.0f, NULL, &count) < targets[i]) ||
            !(solar_at(20.25, 0.0f, upper, 1000.0f, NULL, &count) >= targets[i])) return 2;
        solar_at(20.25, 0.0f, lower, 1000.0f, "horizon", &count);
        solar_at(20.25, 0.0f, upper, 1000.0f, "horizon", &count);
    }

    /* A large input obtains the capped irradiance. Probe its neighbors at
       several solar elevations and confirm overshoot reaches the same cap. */
    for (i = 0; i < sizeof(latitudes) / sizeof(latitudes[0]); ++i) {
        float cap = solar_at(20.5, latitudes[i], 0.0f, 5000.0f, "cap", &count);
        if (!isfinite(cap) || cap <= 0.0f) return 2;
        for (side = -1; side <= 1; ++side) {
            float input = side == 0 ? cap : nextafterf(cap, side < 0 ? -INFINITY : INFINITY);
            float adjusted = solar_at(20.5, latitudes[i], 0.0f, input, "cap-neighbor", &count);
            if (!isfinite(adjusted) || adjusted > cap) return 2;
        }
    }
    return 0;
}
