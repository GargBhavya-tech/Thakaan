// Laptop sanity check for the lwbgt build used by the app: gcc -O2 -I assets/lwbgt/src/include tools/wbgt_host_check.c assets/lwbgt/src/src/wbgt.c -lm
// Prints WBGT for a few Bengaluru conditions so a human (or tools/wbgt_host_check.ps1) can see the numbers are physical.
#include <stdio.h>
#include "lwbgt.h"

static void run(const char *name, int hour, double solar, double t, double rh, double wind) {
    float ws = 0, tg = 0, tnwb = 0, w = 0;
    int st = calc_wbgt(2026, 10, 10, hour, 0, 5, 10, 12.97, 77.59, solar, 910.0, t, rh, wind, 2.0, -0.5, 1, &ws, &tg, &tnwb, NULL, &w);
    printf("%-22s status=%d wbgt=%.2f globe=%.2f nwb=%.2f wind=%.2f\n", name, st, w, tg, tnwb, ws);
}

int main(void) {
    run("noon sun 34C 45%", 12, 900.0, 34.0, 45.0, 1.5);
    run("noon sun 38C 30%", 12, 950.0, 38.0, 30.0, 1.0);
    run("shade-ish 34C 45%", 12, 150.0, 34.0, 45.0, 1.5);
    run("night 24C 70%", 23, 0.0, 24.0, 70.0, 1.0);
    run("invalid rh 150", 12, 900.0, 34.0, 150.0, 1.5);
    return 0;
}
