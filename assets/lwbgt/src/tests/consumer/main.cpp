#include <lwbgt.h>

#include <cstddef>
#include <type_traits>

static_assert(sizeof(lwbgt_input_v1) == 104, "unexpected input ABI size");
static_assert(sizeof(lwbgt_output_v1) == 24, "unexpected output ABI size");
static_assert(offsetof(lwbgt_input_v1, latitude_deg_north) == 32,
              "unexpected input ABI offset");
static_assert(offsetof(lwbgt_output_v1, wbgt_c) == 20,
              "unexpected output ABI offset");
static_assert(std::is_standard_layout<lwbgt_input_v1>::value,
              "input must have standard layout");
static_assert(std::is_standard_layout<lwbgt_output_v1>::value,
              "output must have standard layout");

int main()
{
    lwbgt_input_v1 input = {
        2024, 3, 21, 7, 0, 0, 0, 0,
        14.515380, -91.977540, 0.0, 1010.0, 42.543334, 31.5,
        0.821752, 2.0, 0.0
    };
    lwbgt_output_v1 output = {};

    if (lwbgt_calc_batch_v1(&input, &output, 1) != LWBGT_BATCH_OK || output.status != 0)
        return 1;
    return lwbgt_calc_batch_ex_v1(&input, &output, 1,
            LWBGT_SKIP_PSYCHROMETRIC_WET_BULB) != LWBGT_BATCH_OK ||
        output.status != 0 || output.psychrometric_wet_bulb_c != -9999.0f;
}
