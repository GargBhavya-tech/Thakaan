"""Trains the four small models on SIMULATED data and exports them for the app.

Honest label: there are no labelled real rides yet, so every model here is trained on a simple simulator written in this
file. They run in SHADOW MODE in the app: shown in Diagnostics next to the rule-based code, never deciding a level. The
one exception is the strain forecast, where the app takes max(rule, model) so a model can only raise a warning, never
lower it (CLAUDE.md invariant 3). Replace the simulators with real recordings (ticket #7 replay files) and re-run.

Usage (needs tensorflow, xgboost, scikit-learn; use a throwaway venv):
    python tools/models/train_models.py app/src/main/assets/models
Outputs (gitignored, regenerate with this script, fixed seeds):
    activity_cnn.tflite    input [1,100,3] 2 s of accelerometer at 50 Hz, gravity removed per axis -> [1,5] probabilities
                           classes = IDLE, RIDING, WALKING, STAIRS, LIFTING (same order as motion/Activity.kt)
    scg_denoiser.tflite    input [1,500,1] 5 s of chest accelerometer at 100 Hz -> [1,500,1] cleaned signal
    strain_gru.tflite      input [1,20,2] last 20 min of (TSS/100, sun share) -> [1,2] TSS at +15 and +30 min (/100)
    los_xgboost.json       XGBoost dump of the line-of-sight classifier, features in the order of FEATURES below
"""
import json
import sys
from pathlib import Path

import numpy as np

out = Path(sys.argv[1] if len(sys.argv) > 1 else "models")
out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(2026)


def to_tflite(model, path):
    import tensorflow as tf
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    (out / path).write_bytes(conv.convert())
    print("wrote", path, (out / path).stat().st_size, "bytes")


# ---------------------------------------------------------------- activity
FS_ACT, N_ACT = 50, 100
CLASSES = ["IDLE", "RIDING", "WALKING", "STAIRS", "LIFTING"]


def random_rotation():
    q = rng.standard_normal(4); q /= np.linalg.norm(q)
    w, x, y, z = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def activity_window(c):
    t = np.arange(N_ACT) / FS_ACT
    a = np.zeros((N_ACT, 3))  # body frame: z up
    noise = rng.uniform(0.02, 0.08)
    if c == 0:  # idle, maybe a small drift
        a += noise * rng.standard_normal((N_ACT, 3))
    elif c == 1:  # riding: broadband vibration, little rhythm, engine band 15-24 Hz, sways
        f = rng.uniform(15, 24)
        a[:, 2] += rng.uniform(0.3, 1.2) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28))
        a += rng.uniform(0.2, 0.7) * rng.standard_normal((N_ACT, 3))
        a[:, 0] += rng.uniform(0.0, 0.6) * np.sin(2 * np.pi * rng.uniform(0.2, 0.6) * t)
    elif c == 2:  # walking: 1.4-2.4 Hz, vertical dominant, harmonics
        f = rng.uniform(1.4, 2.4); amp = rng.uniform(1.5, 4.0)
        ph = rng.uniform(0, 6.28)
        a[:, 2] = amp * np.sin(2 * np.pi * f * t + ph) + 0.4 * amp * np.sin(4 * np.pi * f * t + ph)
        a[:, 0] = 0.4 * amp * np.sin(np.pi * f * t + ph)
        a += 0.15 * rng.standard_normal((N_ACT, 3))
    elif c == 3:  # stairs: slower, bigger vertical impacts
        f = rng.uniform(1.0, 1.9); amp = rng.uniform(4.0, 7.0)
        ph = rng.uniform(0, 6.28)
        a[:, 2] = amp * np.sin(2 * np.pi * f * t + ph) + 0.6 * amp * np.sin(4 * np.pi * f * t + 1 + ph)
        a[:, 0] = 0.3 * amp * np.sin(2 * np.pi * f * t + ph + 1)
        a += 0.3 * rng.standard_normal((N_ACT, 3))
    else:  # lifting: heaves, a few big irregular bumps with rotation
        for _ in range(rng.integers(1, 4)):
            c0 = rng.integers(10, N_ACT - 10); w = rng.uniform(3, 8)
            a[:, 2] += rng.uniform(5, 11) * np.exp(-0.5 * ((np.arange(N_ACT) - c0) / w) ** 2)
            a[:, 1] += rng.uniform(-4, 4) * np.exp(-0.5 * ((np.arange(N_ACT) - c0) / w) ** 2)
        a += 0.25 * rng.standard_normal((N_ACT, 3))
    a = a @ random_rotation().T
    return a - a.mean(axis=0, keepdims=True)  # gravity (constant) removed


def build_activity(n_per=1500):
    import tensorflow as tf
    x = np.stack([activity_window(c) for c in range(5) for _ in range(n_per)]).astype("float32")
    y = np.repeat(np.arange(5), n_per)
    idx = rng.permutation(len(y)); x, y = x[idx], y[idx]
    m = tf.keras.Sequential([
        tf.keras.layers.Input((N_ACT, 3)),
        tf.keras.layers.Conv1D(16, 7, activation="relu", padding="same"),
        tf.keras.layers.MaxPool1D(2),
        tf.keras.layers.Conv1D(32, 5, activation="relu", padding="same"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(24, activation="relu"),
        tf.keras.layers.Dense(5, activation="softmax"),
    ])
    m.compile("adam", "sparse_categorical_crossentropy", metrics=["accuracy"])
    m.fit(x, y, epochs=12, batch_size=64, validation_split=0.2, verbose=0)
    xv = np.stack([activity_window(c) for c in range(5) for _ in range(300)]).astype("float32")
    yv = np.repeat(np.arange(5), 300)
    acc = (m.predict(xv, verbose=0).argmax(1) == yv).mean()
    print("activity accuracy on fresh SIMULATED windows: %.3f" % acc)
    to_tflite(m, "activity_cnn.tflite")
    return acc


# ---------------------------------------------------------------- SCG denoiser
FS_SCG, N_SCG = 100, 500


def scg_pair():
    t = np.arange(N_SCG) / FS_SCG
    hr = rng.uniform(50, 130) / 60.0
    beat_times = np.cumsum(rng.normal(1 / hr, 0.03 / hr, 12)) - rng.uniform(0, 1 / hr)
    clean = np.zeros(N_SCG)
    for bt in beat_times:  # AO complex: a short damped oscillation
        k = t - bt
        m = (k > 0) & (k < 0.25)
        clean[m] += np.exp(-k[m] / 0.04) * np.sin(2 * np.pi * rng.uniform(14, 20) * k[m])
    resp = 0.8 * np.sin(2 * np.pi * rng.uniform(0.2, 0.35) * t + rng.uniform(0, 6.28))
    walk_like = rng.uniform(0, 1.5) * np.sin(2 * np.pi * rng.uniform(1.5, 2.3) * t) * (rng.random() < 0.3)
    noisy = clean + resp + walk_like + rng.uniform(0.1, 0.6) * rng.standard_normal(N_SCG)
    # target: the clean cardiac component, normalised like the input
    return noisy, clean


def build_scg(n=4000):
    import tensorflow as tf
    pairs = [scg_pair() for _ in range(n)]
    x = np.stack([p[0] for p in pairs]).astype("float32")[..., None]
    y = np.stack([p[1] for p in pairs]).astype("float32")[..., None]
    m = tf.keras.Sequential([
        tf.keras.layers.Input((N_SCG, 1)),
        tf.keras.layers.Conv1D(16, 9, padding="same", activation="relu"),
        tf.keras.layers.Conv1D(24, 9, padding="same", activation="relu", dilation_rate=2),
        tf.keras.layers.Conv1D(24, 9, padding="same", activation="relu", dilation_rate=4),
        tf.keras.layers.Conv1D(1, 9, padding="same"),
    ])
    m.compile("adam", "mse")
    m.fit(x, y, epochs=10, batch_size=32, validation_split=0.1, verbose=0)
    fresh = [scg_pair() for _ in range(200)]
    xv = np.stack([p[0] for p in fresh]).astype("float32")[..., None]
    yv = np.stack([p[1] for p in fresh]).astype("float32")[..., None]
    pred = m.predict(xv, verbose=0)
    corr = np.mean([np.corrcoef(pred[i, :, 0], yv[i, :, 0])[0, 1] for i in range(len(xv))])
    base = np.mean([np.corrcoef(xv[i, :, 0], yv[i, :, 0])[0, 1] for i in range(len(xv))])
    print("scg: correlation with the clean cardiac signal, noisy input %.3f -> denoised %.3f" % (base, corr))
    to_tflite(m, "scg_denoiser.tflite")
    return base, corr


# ---------------------------------------------------------------- strain GRU
def tss_path(n=20 + 30):
    """Simulated strain-score minutes: sun share steps, the score rises with exposure and falls slowly in shade."""
    sun = np.clip(np.cumsum(rng.normal(0, 0.12, n)) * 0.3 + rng.uniform(0.2, 0.9), 0, 1)
    tss = np.zeros(n); tss[0] = rng.uniform(15, 70)
    drive = rng.uniform(0.2, 0.9)
    for i in range(1, n):
        up = drive * sun[i] * rng.uniform(0.6, 1.4)
        down = 0.25 * (1 - sun[i])
        tss[i] = np.clip(tss[i - 1] + up - down + rng.normal(0, 0.4), 0, 100)
    return tss, sun


def build_gru(n=6000):
    import tensorflow as tf
    xs, ys = [], []
    for _ in range(n):
        tss, sun = tss_path()
        xs.append(np.stack([tss[:20] / 100.0, sun[:20]], 1))
        ys.append([tss[19 + 15] / 100.0, tss[19 + 30 - 1] / 100.0 if 19 + 30 < len(tss) else tss[-1] / 100.0])
    x = np.array(xs, "float32"); y = np.array(ys, "float32")
    m = tf.keras.Sequential([
        tf.keras.layers.Input((20, 2)),
        tf.keras.layers.GRU(24, unroll=True),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(2),
    ])
    m.compile("adam", "mse")
    m.fit(x, y, epochs=15, batch_size=64, validation_split=0.1, verbose=0)
    fx, fy = [], []
    for _ in range(500):
        tss, sun = tss_path()
        fx.append(np.stack([tss[:20] / 100.0, sun[:20]], 1)); fy.append([tss[34] / 100.0, tss[-1] / 100.0])
    fx = np.array(fx, "float32"); fy = np.array(fy, "float32")
    mae = np.abs(m.predict(fx, verbose=0) - fy).mean(0) * 100
    # baseline: linear extrapolation of the last 20 min, as the app does today (without the no-falling clamp)
    base = []
    for i in range(len(fx)):
        s = fx[i, :, 0] * 100; k = np.polyfit(np.arange(20), s, 1)
        base.append([abs(s[-1] + k[0] * 15 - fy[i, 0] * 100), abs(s[-1] + k[0] * 30 - fy[i, 1] * 100)])
    print("gru MAE (TSS points) +15/+30: %.2f / %.2f; linear baseline %.2f / %.2f" % (mae[0], mae[1], *np.mean(base, 0)))
    to_tflite(m, "strain_gru.tflite")
    return mae, np.mean(base, 0)


# ---------------------------------------------------------------- GNSS line of sight
FEATURES = ["cn0", "delta_cn0", "elevation", "doppler_residual", "l1_l5_gap"]


def sat_sample(los):
    el = rng.uniform(5, 85)
    if los:
        cn0 = 25 + 0.18 * el + rng.normal(0, 2.5) + 6
        d = rng.normal(0, 1.5); doppler = abs(rng.normal(0, 0.25)); gap = rng.normal(0.8, 1.8)
    else:
        cn0 = 25 + 0.18 * el + rng.normal(0, 4.0) - rng.uniform(6, 20)
        d = rng.normal(-4, 4); doppler = abs(rng.normal(0, 0.9)) + rng.uniform(0, 0.4); gap = rng.normal(5.5, 3.0)
    return [cn0, d, el, doppler, gap]


def build_xgb(n=20000):
    import xgboost as xgb
    y = rng.integers(0, 2, n)
    x = np.array([sat_sample(bool(v)) for v in y])
    nt = int(n * 0.8)
    clf = xgb.XGBClassifier(n_estimators=40, max_depth=3, learning_rate=0.3, base_score=0.5)
    clf.fit(x[:nt], y[:nt])
    acc = (clf.predict(x[nt:]) == y[nt:]).mean()
    print("xgb accuracy on held-out SIMULATED satellites: %.3f" % acc)
    booster = clf.get_booster()
    trees = [json.loads(t) for t in booster.get_dump(dump_format="json")]
    (out / "los_xgboost.json").write_text(json.dumps({"features": FEATURES, "base_score": 0.5, "trees": trees}))
    print("wrote los_xgboost.json")
    return acc


if __name__ == "__main__":
    report = {"trained_on": "simulated data (tools/models/train_models.py), not real rides"}
    report["activity_accuracy"] = float(build_activity())
    b, c = build_scg(); report["scg_corr_noisy"] = float(b); report["scg_corr_denoised"] = float(c)
    mae, base = build_gru(); report["gru_mae"] = [float(v) for v in mae]; report["linear_mae"] = [float(v) for v in base]
    report["xgb_accuracy"] = float(build_xgb())
    (out / "models_report.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))
