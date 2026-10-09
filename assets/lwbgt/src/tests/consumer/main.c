#include <lwbgt.h>

int main(void)
{
    lwbgt_input_v1 input = {
        2024, 3, 21, 7, 0, 0, 0, 0,
        14.515380, -91.977540, 0.0, 1010.0, 42.543334, 31.5,
        0.821752, 2.0, 0.0
    };
    lwbgt_output_v1 output;

    if (lwbgt_calc_batch_v1(&input, &output, 1) != LWBGT_BATCH_OK || output.status != 0)
        return 1;
    return lwbgt_calc_batch_ex_v1(&input, &output, 1,
            LWBGT_SKIP_PSYCHROMETRIC_WET_BULB) != LWBGT_BATCH_OK ||
        output.status != 0 || output.psychrometric_wet_bulb_c != -9999.0f;
}
