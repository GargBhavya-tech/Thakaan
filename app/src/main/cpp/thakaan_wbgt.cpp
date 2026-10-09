#include <jni.h>
#include "lwbgt.h"

// JNI bridge for com.thakaan.exposure.WbgtNative. Returns a double[5]:
// [status(0 = ok), wbgt, globe, naturalWetBulb, estimatedWind] or null when the call itself is invalid.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_thakaan_exposure_WbgtNative_calc(
    JNIEnv *env, jobject /*thiz*/,
    jint year, jint month, jint day, jint hour, jint minute, jint gmtHours, jint averagingMinutes,
    jdouble lat, jdouble lon, jdouble solar, jdouble pressureHpa, jdouble tempC, jdouble rhPercent,
    jdouble windMs, jdouble windHeightM, jdouble vertDiffC, jint urban) {
    float wind = 0, globe = 0, nwb = 0, wbgt = 0;
    // psychrometric wet bulb is not needed; NULL skips its calculation
    int status = calc_wbgt(year, month, day, hour, minute, gmtHours, averagingMinutes, lat, lon, solar,
                           pressureHpa, tempC, rhPercent, windMs, windHeightM, vertDiffC, urban,
                           &wind, &globe, &nwb, nullptr, &wbgt);
    jdoubleArray out = env->NewDoubleArray(5);
    if (out == nullptr) return nullptr;
    jdouble v[5] = {(jdouble) status, wbgt, globe, nwb, wind};
    env->SetDoubleArrayRegion(out, 0, 5, v);
    return out;
}
