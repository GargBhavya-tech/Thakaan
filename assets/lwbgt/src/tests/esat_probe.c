#include "lwbgt.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv)
{
    char line[128];
    size_t count = 0;
    FILE *stream;

    if (argc != 2 || (stream = fopen(argv[1], "r")) == NULL) return 2;
    if (fgets(line, sizeof(line), stream) == NULL) return 2;
    puts("case_id,esat");
    while (fgets(line, sizeof(line), stream) != NULL) {
        double temperature;
        int phase;
        float value;
        uint32_t bits;
        if (sscanf(line, "%lf,%d", &temperature, &phase) != 2 ||
            (phase != 0 && phase != 1)) return 2;
        value = esat(temperature, phase);
        memcpy(&bits, &value, sizeof(bits));
        printf("esat-%05zu,%08x\n", ++count, (unsigned int)bits);
    }
    if (ferror(stream) || fclose(stream) || count == 0) return 2;
    return 0;
}
