package com.thakaan.smoke;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.GnssCapabilities;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ticket #2 loaner smoke test. Throwaway probe, not part of the app.
 * Every line is logged as "THK_SMOKE" so a laptop can grep it from logcat.
 */
public class SmokeActivity extends Activity {
    private static final String TAG = "THK_SMOKE";
    private static final long GNSS_WINDOW_S = 30;
    private final StringBuilder out = new StringBuilder();
    private TextView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new TextView(this);
        view.setTextSize(12f);
        view.setPadding(16, 16, 16, 16);
        ScrollView sv = new ScrollView(this);
        sv.addView(view);
        setContentView(sv);
        new Thread(this::runAll, "smoke").start();
    }

    private void line(String s) {
        Log.i(TAG, s);
        synchronized (out) { out.append(s).append('\n'); }
        runOnUiThread(() -> { synchronized (out) { view.setText(out.toString()); } });
    }

    private void runAll() {
        line("SOC model=" + Build.SOC_MODEL + " manufacturer=" + Build.SOC_MANUFACTURER
                + " device=" + Build.MODEL + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT);
        sensors();
        torch();
        thermal();
        tts();
        gnss();
        line("DONE");
    }

    private void sensors() {
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        line("SENSORS total=" + all.size());
        boolean light = false, cct = false, flicker = false, accel = false;
        for (Sensor s : all) {
            String t = s.getStringType().toLowerCase(Locale.ROOT);
            String n = s.getName().toLowerCase(Locale.ROOT);
            boolean isLight = s.getType() == Sensor.TYPE_LIGHT;
            boolean isCct = t.contains("cct");
            boolean isFlicker = t.contains("flicker") || n.contains("flicker");
            boolean isAccel = s.getType() == Sensor.TYPE_ACCELEROMETER;
            if (isLight || isCct || isFlicker || isAccel || t.contains("light")) {
                double maxHz = s.getMinDelay() > 0 ? 1e6 / s.getMinDelay() : Double.NaN;
                line("SENSOR name=" + s.getName() + " type=" + s.getStringType() + "(" + s.getType() + ")"
                        + " maxRange=" + s.getMaximumRange() + " resolution=" + s.getResolution()
                        + " minDelayUs=" + s.getMinDelay() + " maxRateHz=" + maxHz
                        + " reportingMode=" + s.getReportingMode() + " vendor=" + s.getVendor());
            }
            light |= isLight; cct |= isCct; flicker |= isFlicker; accel |= isAccel;
        }
        line("RESULT light=" + light + " cct=" + cct + " flicker=" + flicker + " accel=" + accel);
    }

    private void torch() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)) {
                    Integer max = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                    Integer def = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL);
                    line("TORCH camera=" + id + " facing=" + facing + " maxLevel=" + max + " defaultLevel=" + def);
                }
            }
        } catch (Throwable t) {
            line("TORCH error=" + t);
        }
    }

    private void thermal() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            for (int i = 0; i < 3; i++) {
                float h = pm.getThermalHeadroom(10);
                line("THERMAL headroom(10s)=" + h + " status=" + pm.getCurrentThermalStatus());
                Thread.sleep(1200);
            }
        } catch (Throwable t) {
            line("THERMAL error=" + t);
        }
    }

    private void tts() {
        CountDownLatch ready = new CountDownLatch(1);
        final int[] status = {-1};
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(this, st -> { status[0] = st; ready.countDown(); });
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) {
                line("TTS init failed status=" + status[0]);
                return;
            }
            TextToSpeech tts = holder[0];
            line("TTS engine=" + tts.getDefaultEngine());
            for (String tag : new String[]{"hi-IN", "kn-IN", "ta-IN", "te-IN", "en-IN"}) {
                Locale l = Locale.forLanguageTag(tag);
                int avail = tts.isLanguageAvailable(l);
                int offline = 0, network = 0;
                String sample = "";
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (v.getLocale().toLanguageTag().equalsIgnoreCase(tag)) {
                            if (v.isNetworkConnectionRequired()) network++; else { offline++; if (sample.isEmpty()) sample = v.getName(); }
                        }
                    }
                }
                line("TTS " + tag + " isLanguageAvailable=" + avail + " (>=0 usable, 2 = country+variant)"
                        + " offlineVoices=" + offline + " networkOnlyVoices=" + network + " sample=" + sample);
            }
            tts.shutdown();
        } catch (Throwable t) {
            line("TTS error=" + t);
        }
    }

    private void gnss() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            GnssCapabilities caps = lm.getGnssCapabilities();
            line("GNSS capabilities=" + caps);
            line("GNSS year=" + lm.getGnssYearOfHardware() + " model=" + lm.getGnssHardwareModelName());
            final Set<String> bands = new HashSet<>();
            final int[] events = {0}, meas = {0}, l5 = {0}, l5Tracked = {0}, l1 = {0};
            final float[] maxL5Cn0 = {0};
            GnssMeasurementsEvent.Callback cb = new GnssMeasurementsEvent.Callback() {
                @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent e) {
                    events[0]++;
                    for (GnssMeasurement m : e.getMeasurements()) {
                        meas[0]++;
                        double f = m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : 0;
                        long mhz = Math.round(f / 1e6);
                        bands.add(m.getConstellationType() + ":" + mhz);
                        boolean isL5 = Math.abs(f - 1176.45e6) < 2e6 || Math.abs(f - 1207.14e6) < 2e6;
                        boolean isL1 = Math.abs(f - 1575.42e6) < 2e6;
                        if (isL5) { l5[0]++; maxL5Cn0[0] = Math.max(maxL5Cn0[0], (float) m.getCn0DbHz());
                            if ((m.getState() & GnssMeasurement.STATE_CODE_LOCK) != 0) l5Tracked[0]++; }
                        if (isL1) l1[0]++;
                    }
                }
            };
            GnssMeasurementRequest req = new GnssMeasurementRequest.Builder().setFullTracking(true).build();
            line("GNSS registering fullTracking=true for " + GNSS_WINDOW_S + "s (needs sky view; go to a window or outdoors)");
            boolean ok = lm.registerGnssMeasurementsCallback(req, Executors.newSingleThreadExecutor(), cb);
            line("GNSS register accepted=" + ok);
            Thread.sleep(GNSS_WINDOW_S * 1000);
            lm.unregisterGnssMeasurementsCallback(cb);
            line("GNSS events=" + events[0] + " measurements=" + meas[0] + " L1=" + l1[0]
                    + " L5/E5a=" + l5[0] + " L5withCodeLock=" + l5Tracked[0] + " maxL5Cn0=" + maxL5Cn0[0]);
            line("GNSS bands(constellation:MHz)=" + bands);
        } catch (SecurityException se) {
            line("GNSS needs location permission: adb shell pm grant com.thakaan.smoke android.permission.ACCESS_FINE_LOCATION");
        } catch (Throwable t) {
            line("GNSS error=" + t);
        }
    }
}
