"""Ticket #4 test: every staged asset opens, and its size and date are logged. Also checks what is inside the installed APK
and, with the phone attached, what is in the app's private model folder.

Usage: python tools/verify_assets.py [--adb PATH]   (run from the repo root; writes docs/ASSET_LOG.md)
"""
import datetime
import json
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
rows = []


def log(name, path, how, ok, detail=""):
    p = Path(path)
    size = p.stat().st_size if p.exists() else 0
    date = datetime.datetime.fromtimestamp(p.stat().st_mtime).strftime("%Y-%m-%d") if p.exists() else "-"
    rows.append((name, str(p.relative_to(ROOT)) if p.exists() else str(path), size, date, how, "PASS" if ok else "FAIL", detail))


def check_json(name, path, need=None):
    try:
        d = json.loads(Path(path).read_text(encoding="utf-8"))
        ok = need is None or need(d)
        log(name, path, "parses as JSON", ok, "" if ok else "unexpected content")
    except Exception as e:  # noqa: BLE001
        log(name, path, "parses as JSON", False, str(e)[:80])


def check_zip(name, path):
    try:
        with zipfile.ZipFile(path) as z:
            bad = z.testzip()
        log(name, path, "opens as a zip archive", bad is None, f"bad member {bad}" if bad else "")
    except Exception as e:  # noqa: BLE001
        log(name, path, "opens as a zip archive", False, str(e)[:80])


def check_tflite(name, path):
    b = Path(path).read_bytes()[:8]
    log(name, path, "TFLite header 'TFL3'", b[4:8] == b"TFL3", "")


def check_nonempty_header(name, path, how, n=16):
    b = Path(path).read_bytes()[:n]
    log(name, path, how, len(b) == n, b[:8].hex())


a = ROOT / "assets"
app = ROOT / "app/src/main/assets"
check_json("ECTemp reference vectors", a / "ectemp_vectors.json", lambda d: isinstance(d, (list, dict)) and len(d) > 0)
check_json("Open-Meteo weather cache", a / "weather/bengaluru_open-meteo.json", lambda d: len(d["hourly"]["time"]) > 24)
check_json("Open-Meteo PM2.5 cache", a / "airquality/bengaluru_open-meteo_pm25.json", lambda d: sum(v is not None for v in d["hourly"]["pm2_5"]) > 24)
for f in sorted((a / "osm").glob("*")) if (a / "osm").exists() else []:
    if f.suffix == ".json":
        check_json(f"OSM {f.name}", f)
    else:
        check_nonempty_header(f"OSM {f.name}", f, "readable")
for f in sorted((app / "chhaya").glob("*.json")):
    check_json(f"Chhaya {f.name}", f)
for f in sorted((a / "lwbgt").glob("*.c")) + sorted((a / "lwbgt").glob("*.h")):
    check_nonempty_header(f"lwbgt {f.name}", f, "readable text")
g = a / "models/gemma-4-E2B-it-gpu.litertlm"
if g.exists():
    check_nonempty_header("Gemma 4 E2B", g, "readable, header bytes", 16)
check_zip("MediaPipe face_landmarker.task", app / "models/face_landmarker.task")
for t in ("activity_cnn", "scg_denoiser", "strain_gru", "efficientphys", "segformer_b0_cityscapes"):
    p = app / f"models/{t}.tflite"
    if p.exists():
        check_tflite(t, p)
check_json("line-of-sight trees", app / "models/los_xgboost.json", lambda d: len(d["trees"]) > 0)
for f in sorted((a / "models/vosk").glob("vosk-model-*")) if (a / "models/vosk").exists() else []:
    need = [f / "am/final.mdl", f / "conf/model.conf"]
    log(f"Vosk {f.name}", f, "model files present", all(x.exists() for x in need), "")

apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
phone = []
if apk.exists():
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
    for want in ("assets/ectemp_vectors.json", "assets/weather/bengaluru_open-meteo.json", "assets/airquality/bengaluru_open-meteo_pm25.json",
                 "assets/models/face_landmarker.task", "assets/models/efficientphys.tflite", "assets/models/segformer_b0_cityscapes.tflite",
                 "assets/models/activity_cnn.tflite", "assets/models/scg_denoiser.tflite", "assets/models/strain_gru.tflite", "assets/models/los_xgboost.json",
                 "assets/chhaya/graph.json"):
        phone.append((want, "in the APK" if want in names else "MISSING from the APK"))
adb = sys.argv[sys.argv.index("--adb") + 1] if "--adb" in sys.argv else None
if adb:
    out = subprocess.run([adb, "shell", "run-as", "com.thakaan", "ls", "-la", "files/models"], capture_output=True, text=True).stdout
    for name in ("gemma-4-E2B-it-gpu.litertlm", "vosk-model-small-en-in-0.4", "vosk-model-small-hi-0.22"):
        phone.append((f"files/models/{name}", "on the phone" if name in out else "NOT on the phone"))

lines = ["# Asset log (ticket #4)", "", f"Generated {datetime.datetime.now():%Y-%m-%d %H:%M} by `tools/verify_assets.py`. Every file below was opened on the laptop; size and date are as found.", "",
         "| Asset | File | Size (bytes) | Date | Opened by | Result | Note |", "| --- | --- | ---: | --- | --- | --- | --- |"]
for r in rows:
    lines.append("| " + " | ".join(str(x) for x in r) + " |")
lines += ["", "## On the phone", "", "| Item | State |", "| --- | --- |"] + [f"| {a_} | {b_} |" for a_, b_ in phone]
(ROOT / "docs/ASSET_LOG.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
fails = [r for r in rows if r[5] != "PASS"] + [p for p in phone if "MISSING" in p[1] or "NOT" in p[1]]
print(f"{len(rows)} files checked, {len([r for r in rows if r[5] == 'PASS'])} pass; phone items {len(phone)}; problems: {len(fails)}")
for f in fails:
    print("PROBLEM:", f)
