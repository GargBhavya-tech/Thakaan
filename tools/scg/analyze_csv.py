"""Explains why a Chest lab recording was rejected, window by window, using the same gates as ScgPipeline.kt.

Usage:  python tools/scg/analyze_csv.py path/to/scg_lab_<ts>.csv   (SETTLE=0 python ... to ignore the posture lockout and see what is under it)
Pull a recording off the phone first:
  adb exec-out run-as com.thakaan cat files/scg_lab_<ts>.csv > scg_lab_<ts>.csv

CSV columns (written by ScgLabScreen): t_ms, ax, ay, az (m/s^2), gyro (rad/s magnitude, 0 if no gyroscope).
It prints (1) how the sensor delivered samples (rate, holes), (2) for each 5 s window which gate rejected it
(motion, rotation, tilt/settling, hole) and, for windows that passed, the beat-spacing and autocorrelation rates and
whether they agree, (3) the strongest repeat periods seen, so a double-rate (second heart sound counted) or half-rate
reading is visible. It changes nothing: the gates here mirror the app's constants so the two can be compared.
"""
import os
import sys
import numpy as np
from scipy.interpolate import CubicSpline
from scipy.signal import butter, sosfiltfilt

FS = 100.0
WIN = 5.0; STEP = 2.5
MAX_BODY_MOTION = 0.15; MAX_GYRO_RMS = 0.15; MAX_TILT_DEG = 4.0; POSTURE_SETTLE_SEC = float(os.environ.get('SETTLE', 30.0)); MAX_GAP_SEC = 0.1
METHOD_AGREEMENT = 0.08; MIN_STILL = 6


def bandpass(x, lo, hi, fs=FS):
    sos = butter(2, [lo, hi], btype="band", fs=fs, output="sos")
    return sosfiltfilt(sos, x)


def envelope(x, w=4):
    k = np.ones(2 * w + 1) / (2 * w + 1)
    return np.convolve(np.abs(x), k, mode="same")


def beats_bpm(clean):
    env = envelope(clean)
    thr = env.mean() + 0.8 * env.std()
    min_gap = int(0.33 * FS); peaks = []
    for i in range(1, len(env) - 1):
        if env[i] >= thr and env[i] >= env[i - 1] and env[i] > env[i + 1]:
            if not peaks or i - peaks[-1] >= min_gap: peaks.append(i)
            elif env[i] > env[peaks[-1]]: peaks[-1] = i
    if len(peaks) < 4: return None, peaks, "too few beats (%d)" % len(peaks)
    gaps = np.sort(np.diff(peaks) / FS); med = gaps[len(gaps) // 2]
    regular = np.mean(np.abs(gaps - med) <= 0.15 * med)
    if regular < 0.6: return None, peaks, "beats not regular (%.0f%% near the median gap %.2fs)" % (regular * 100, med)
    bpm = 60 / med
    return (bpm if 40 <= bpm <= 180 else None), peaks, None if 40 <= bpm <= 180 else "rate out of range"


def autocorr_candidates(x, top=3):
    env = envelope(x); e = env - env.mean(); e0 = (e * e).sum()
    if e0 < 1e-9: return []
    lo, hi = int(0.33 * FS), min(int(1.5 * FS), len(e) // 2)
    r = np.array([np.dot(e[:len(e) - l], e[l:]) / e0 for l in range(lo, hi + 1)])
    out = []
    for i in np.argsort(-r):
        l = lo + i
        if all(abs(l - o[0]) > 5 for o in out): out.append((l, r[i]))
        if len(out) >= top: break
    return [(60 * FS / l, rr) for l, rr in out]


def principal(b, a, win):
    seg = b[:, a:a + win]; seg = seg - seg.mean(axis=1, keepdims=True)
    u, s, vt = np.linalg.svd(seg, full_matrices=False)
    return u[:, 0] @ seg, int(np.argmax(np.abs(u[:, 0])))


def main(path):
    d = np.loadtxt(path, delimiter=",", skiprows=1)
    t = d[:, 0] / 1000.0; acc = d[:, 1:4].T; gyro = d[:, 4]
    dur = t[-1] - t[0]
    dt = np.diff(t)
    print("== sensor delivery ==")
    print("samples %d in %.1f s = %.0f Hz; sample gap median %.1f ms, 99th pct %.1f ms, max %.0f ms" % (len(t), dur, len(t) / dur, np.median(dt) * 1e3, np.percentile(dt, 99) * 1e3, dt.max() * 1e3))
    holes = [(t[i] - t[0], t[i + 1] - t[0]) for i in np.where(dt > MAX_GAP_SEC)[0]]
    print("holes longer than %.0f ms: %d %s" % (MAX_GAP_SEC * 1e3, len(holes), ["%.1f-%.1fs" % h for h in holes[:8]]))
    print("mean acceleration (gravity) x,y,z = %s  |g| = %.2f; gyro rms %.3f rad/s" % (np.round(acc.mean(axis=1), 2), np.linalg.norm(acc.mean(axis=1)), np.sqrt((gyro ** 2).mean())))
    tt = t - t[0]; grid = np.arange(0, tt[-1], 1 / FS)
    keep = np.concatenate([[True], np.diff(tt) > 0])
    ax3 = np.array([CubicSpline(tt[keep], a[keep])(grid) for a in acc]); gy = CubicSpline(tt[keep], gyro[keep])(grid)
    beat = np.array([bandpass(a, 4, 25) for a in ax3]); slow = np.array([bandpass(a, 0.3, 3.0) for a in ax3])
    win = int(WIN * FS); step = int(STEP * FS)
    print("\n== windows (5 s, step 2.5 s) ==")
    print(" start  motion  rot   tilt  verdict")
    prevg = None; settle = -1.0; counts = {"motion": 0, "rotation": 0, "tilt/settling": 0, "hole": 0, "passed": 0}
    still_rates = []; agree = 0; peak_only = 0; total = 0
    a = 0
    while a + win <= len(grid):
        total += 1
        t0 = a / FS; t1 = (a + win) / FS
        g = ax3[:, a:a + win].mean(axis=1)
        tilt = 0.0 if prevg is None else float(np.degrees(np.arccos(np.clip(prevg @ g / (np.linalg.norm(prevg) * np.linalg.norm(g)), -1, 1))))
        prevg = g
        if tilt > MAX_TILT_DEG: settle = t1 + POSTURE_SETTLE_SEC
        motion = max(s[a:a + win].std(ddof=1) for s in slow); rot = float(np.sqrt((gy[a:a + win] ** 2).mean()))
        hole = any(h[1] > t0 and h[0] < t1 for h in holes)
        reasons = []
        if motion > MAX_BODY_MOTION: reasons.append("motion")
        if rot > MAX_GYRO_RMS: reasons.append("rotation")
        if hole: reasons.append("hole")
        if t0 < settle: reasons.append("tilt/settling")
        line = "%5.1f  %6.3f  %5.3f  %4.1f  " % (t0, motion, rot, tilt)
        if reasons:
            for r in reasons: counts[r] += 1
            line += "REJECT: " + ", ".join(reasons)
        else:
            counts["passed"] += 1
            wave, axis = principal(beat, a, win)
            w = (wave - wave.mean()) / max(wave.std(), 1e-9)
            pk, peaks, why = beats_bpm(w); ac = autocorr_candidates(w)
            acb = ac[0][0] if ac and ac[0][1] >= 0.2 else None
            ok = pk is not None and acb is not None and abs(pk - acb) <= METHOD_AGREEMENT * acb
            if ok: still_rates.append(pk); agree += 1
            elif pk is not None: peak_only += 1
            line += "pass  peaks=%s  autocorr=%s (%s)  axis=%s" % (
                "%.0f" % pk if pk else "-", ", ".join("%.0f@%.2f" % c for c in ac), "agree" if ok else ("peaks: " + why if pk is None else "disagree"), "xyz"[axis])
        print(line)
        a += step
    print("\n== summary ==")
    print("windows: %d  passed the stillness gates: %d  (needs >= %d)" % (total, counts["passed"], MIN_STILL))
    print("rejected by: motion %d, rotation %d, hole %d, tilt/settling %d  (a window can have several reasons)" % (counts["motion"], counts["rotation"], counts["hole"], counts["tilt/settling"]))
    print("windows where both rate methods agreed: %d; peaks only: %d" % (agree, peak_only))
    if still_rates: print("agreed rates: %s  median %.1f" % (np.round(still_rates, 0), np.median(still_rates)))
    if counts["passed"] < MIN_STILL:
        worst = max(("motion", "rotation", "hole", "tilt/settling"), key=lambda k: counts[k])
        print("verdict: not enough still windows. Biggest cause: %s" % worst)
    elif len(still_rates) < MIN_STILL:
        print("verdict: still enough but no clear beat: the two rate methods rarely agreed (a second autocorrelation candidate near double or half the first points to counting the second heart sound).")
    else:
        print("verdict: would be accepted")


if __name__ == "__main__":
    main(sys.argv[1])
