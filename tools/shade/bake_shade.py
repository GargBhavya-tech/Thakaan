"""Bake shade from satellite-derived heights into an H3 (resolution 11) table, 4 seasons x 30-minute slots.

Inputs (from fetch_window.py, gitignored): Open Buildings 2.5D Temporal height + presence (CC BY 4.0), Meta/WRI canopy height (CC BY 4.0).
Method: sample the road centrelines every 4 m; for each sun position march a ray from the sample towards the sun over the height
surface; buildings block fully, trees count as 0.6 shade (partial: a tree is not a wall) and a tree directly overhead shades
whatever the sun angle. A cell's value is the mean over the road samples inside it. Outputs, under app/src/main/assets/chhaya/:
  shade_h3.bin             the H3-keyed table (format below)
  shade_obc_<doy>.json     per-segment shade in the same format as shade.json, so ShadeLayer reads it unchanged
Segments whose shade changes by more than 20 points on average when building heights are scaled by 0.7 / 1.3 are flagged low
confidence (the height model was validated only in North America, Europe and Japan).
Limits: flat-ground assumption, UTM grid convergence (0.6 degrees here) ignored, shadows from outside the window margin missed.

shade_h3.bin (little endian): "THH3", u8 resolution, u8 seasons, u8 slots, u8 pad, u16 firstSlotMinute, u16 slotStepMinutes,
  u16 doy[seasons], u32 cells, u64 h3[cells] (sorted), then u8 shade[seasons][slots][cells] (0 = full sun, 255 = full shade).
"""
import json, math, struct
import numpy as np, h3
from affine import Affine
from pyproj import Transformer
from rasterio.warp import reproject, Resampling

SRC = "assets/shade_src"; OUT = "app/src/main/assets/chhaya"
LAT0, LON0 = 12.975, 77.60
SEASONS = [79, 172, 265, 355]          # 20 Mar, 21 Jun, 22 Sep, 21 Dec (day of year)
FIRST, STEP, NSLOT = 330, 30, 28       # 05:30 .. 19:00 local (IST), covers sunrise to sunset in every season
TREE = 0.6                             # shade value of a tree's shadow (partial); to be calibrated against the light sensor
RES_H3 = 11
SAMPLE_M = 4.0
START_M, MAX_M = 4.0, 440.0            # ignore the first 4 m (OSM centreline vs roof offset); the window margin limits the reach


def sun(doy, minute_local):
    """Solar elevation and azimuth (degrees, azimuth clockwise from north) for the demo area, NOAA low-precision formulas."""
    g = 2 * math.pi / 365 * (doy - 1 + (minute_local / 60 - 5.5 - 12) / 24)
    eqt = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g) - 0.014615 * math.cos(2 * g) - 0.040849 * math.sin(2 * g))
    decl = 0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g) + 0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g)
    tst = minute_local + eqt + 4 * LON0 - 60 * 5.5
    ha = math.radians(tst / 4 - 180)
    lat = math.radians(LAT0)
    cz = math.sin(lat) * math.sin(decl) + math.cos(lat) * math.cos(decl) * math.cos(ha)
    zen = math.acos(max(-1, min(1, cz)))
    az = math.atan2(math.sin(ha), math.cos(ha) * math.sin(lat) - math.tan(decl) * math.cos(lat)) + math.pi
    return 90 - math.degrees(zen), math.degrees(az) % 360


def march(col0, row0, H, res, el, az):
    """True where the ray from each sample towards the sun meets something taller than the ray."""
    t = math.tan(math.radians(el)); dx = math.sin(math.radians(az)); dy = math.cos(math.radians(az))
    blocked = np.zeros(col0.size, bool)
    hmax = float(H.max()); d = START_M
    while d <= MAX_M and d * t <= hmax:
        c = np.rint(col0 + dx * d / res).astype(int); r = np.rint(row0 - dy * d / res).astype(int)
        ok = (c >= 0) & (c < H.shape[1]) & (r >= 0) & (r < H.shape[0])
        h = np.zeros(col0.size, np.float32); h[ok] = H[r[ok], c[ok]]
        blocked |= h > d * t
        d += res
    return blocked


def main():
    meta = json.load(open(f"{SRC}/window.json"))
    bh = np.load(f"{SRC}/bld_height.npy"); bp = np.load(f"{SRC}/bld_presence.npy"); canopy = np.load(f"{SRC}/canopy.npy")
    b = meta["buildings"]; res = abs(b["pixel_m"][0])
    tb = Affine(res, 0, b["origin"][0], 0, -res, b["origin"][1])
    Hb = np.where(bp > 0.5, bh, 0).astype("float32")
    c = meta["canopy"]
    tc = Affine(c["pixel_m"][0], 0, c["origin"][0], 0, c["pixel_m"][1], c["origin"][1])
    Hc = np.zeros(Hb.shape, np.float32)
    reproject(canopy.astype("float32"), Hc, src_transform=tc, src_crs=c["crs"], dst_transform=tb, dst_crs=b["crs"], resampling=Resampling.max)
    Hc[Hb > 0] = 0                       # a tree drawn over a roof is the height model's artefact, not a second obstacle
    print("grid", Hb.shape, "res", res, "building px %.3f tree px(>2m) %.3f" % ((Hb > 0).mean(), (Hc > 2).mean()))

    g = json.load(open(f"{OUT}/graph.json"))
    to_utm = Transformer.from_crs("EPSG:4326", b["crs"], always_xy=True)
    pts, owner = [], []
    for ei, e in enumerate(g["edges"]):
        geom = e[5]; ll = [(geom[i] / 1e6, geom[i + 1] / 1e6) for i in range(0, len(geom), 2)]
        xs, ys = to_utm.transform([p[1] for p in ll], [p[0] for p in ll])
        for k in range(len(xs) - 1):
            L = math.hypot(xs[k + 1] - xs[k], ys[k + 1] - ys[k]); n = max(1, int(L // SAMPLE_M))
            for j in range(n):
                f = (j + 0.5) / n
                pts.append((xs[k] + (xs[k + 1] - xs[k]) * f, ys[k] + (ys[k + 1] - ys[k]) * f, ll[k][0] + (ll[k + 1][0] - ll[k][0]) * f, ll[k][1] + (ll[k + 1][1] - ll[k][1]) * f))
                owner.append(ei)
    P = np.array(pts); owner = np.array(owner); ne = len(g["edges"])
    col0 = (P[:, 0] - tb.c) / res; row0 = (tb.f - P[:, 1]) / res
    inside = (col0 >= 0) & (col0 < Hb.shape[1]) & (row0 >= 0) & (row0 < Hb.shape[0])
    print("road samples", len(P), "inside window", int(inside.sum()))
    cells = np.array([h3.str_to_int(h3.latlng_to_cell(la, lo, RES_H3)) for la, lo in P[:, 2:4]], dtype=np.uint64)
    ucells, cell_idx = np.unique(cells, return_inverse=True)
    overhead = Hc[np.clip(np.rint(row0).astype(int), 0, Hb.shape[0] - 1), np.clip(np.rint(col0).astype(int), 0, Hb.shape[1] - 1)] >= 3.0

    slots = [FIRST + STEP * i for i in range(NSLOT)]
    table = np.zeros((len(SEASONS), NSLOT, len(ucells)), np.uint8)
    edge_json = {}
    for si, doy in enumerate(SEASONS):
        edge_shade = np.zeros((ne, NSLOT)); edge_sens = np.zeros((ne, NSLOT))
        per_edge_n = np.maximum(np.bincount(owner, minlength=ne), 1)
        for ti, m in enumerate(slots):
            el, az = sun(doy, m)
            if el <= 2.0:
                s = np.ones(len(P)); sens = np.zeros(len(P))
            else:
                bld = march(col0, row0, Hb, res, el, az); tree = march(col0, row0, Hc, res, el, az) | overhead
                s = np.maximum(bld.astype(float), TREE * tree)
                lo = march(col0, row0, Hb * 0.7, res, el, az); hi = march(col0, row0, Hb * 1.3, res, el, az)
                sens = np.abs(np.maximum(hi.astype(float), TREE * tree) - np.maximum(lo.astype(float), TREE * tree))
            cv = np.bincount(cell_idx, s, len(ucells)) / np.maximum(np.bincount(cell_idx, minlength=len(ucells)), 1)
            table[si, ti] = np.rint(cv * 255).astype(np.uint8)
            edge_shade[:, ti] = np.bincount(owner, cv[cell_idx], ne) / per_edge_n      # edge value read from the cell table
            edge_sens[:, ti] = np.bincount(owner, sens, ne) / per_edge_n
        day = np.array([sun(doy, m)[0] > 2.0 for m in slots])
        lowconf = (edge_sens[:, day].mean(axis=1) > 0.20).astype(int)
        edge_json[doy] = {"slots": slots, "day_of_year": doy, "shade": np.rint(edge_shade * 100).astype(int).tolist(), "lowconf": lowconf.tolist(),
                          "source": "Open Buildings 2.5D Temporal 2023 (CC BY 4.0) + Meta/WRI canopy height (CC BY 4.0)"}
        with open(f"{OUT}/shade_obc_{doy}.json", "w") as f:
            json.dump(edge_json[doy], f, separators=(",", ":"))
        print("season", doy, "noon sun (el, az)", [round(x, 1) for x in sun(doy, 720)], "mean daytime shade %.2f" % edge_shade[:, day].mean(), "low-confidence segments %.2f" % lowconf.mean())

    with open(f"{OUT}/shade_h3.bin", "wb") as f:
        f.write(b"THH3" + struct.pack("<BBBBHH", RES_H3, len(SEASONS), NSLOT, 0, FIRST, STEP) + struct.pack(f"<{len(SEASONS)}H", *SEASONS) + struct.pack("<I", len(ucells)))
        f.write(ucells.astype("<u8").tobytes()); f.write(table.tobytes())
    print("H3 cells", len(ucells), "table bytes", 16 + 2 * len(SEASONS) + 8 * len(ucells) + table.size)

    # comparison with the OSM-based layer already shipped (today is day 283, nearest season 265)
    old = json.load(open(f"{OUT}/shade.json")); os_ = np.array(old["shade"], float) / 100.0; oslots = old["slots"]
    new = np.array(edge_json[265]["shade"], float) / 100.0
    common = [m for m in oslots if m in slots]
    oi = [oslots.index(m) for m in common]; ni = [slots.index(m) for m in common]
    a = os_[:, oi]; n_ = new[:, ni]
    print("vs OSM layer (day 265 vs 283): mean shade OSM %.2f new %.2f, correlation %.2f, segments differing by >0.3 on average %.2f" % (a.mean(), n_.mean(), np.corrcoef(a.ravel(), n_.ravel())[0, 1], (np.abs(a - n_).mean(axis=1) > 0.3).mean()))


if __name__ == "__main__":
    main()
