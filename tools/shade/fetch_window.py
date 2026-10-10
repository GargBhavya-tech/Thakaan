"""Read only the demo-area window (plus a margin for long shadows) from the two shade datasets; nothing else is downloaded.

  Open Buildings 2.5D Temporal 2023 (Google; CC BY 4.0 or ODbL, we use CC BY 4.0): height + presence bands, read at 2 m.
  Meta/WRI canopy height (CC BY 4.0, source imagery (c) 2016 Maxar): read at native ~1.2 m.
Output: assets/shade_src/{bld_height,bld_presence,canopy}.npy plus window.json (grids and transforms). Gitignored.
"""
import json, sys, time
import numpy as np, rasterio
from rasterio.windows import from_bounds
from rasterio.warp import transform_bounds

BBOX = (12.96, 77.585, 12.99, 77.615)   # south, west, north, east: the graph.json demo area
MARGIN_DEG = 0.004                       # about 450 m: a 100 m building at low sun throws a long shadow into the area
S, W, N, E = BBOX[0] - MARGIN_DEG, BBOX[1] - MARGIN_DEG, BBOX[2] + MARGIN_DEG, BBOX[3] + MARGIN_DEG
BLD = "https://storage.googleapis.com/open-buildings-temporal-data/v1/geotiffs/3bae4_2023_06_30/tile_hXqKtZuLh78.tif"
CHM = "https://dataforgood-fb-data.s3.amazonaws.com/forests/v1/alsgedi_global_v6_float/chm/123303312.tif"
OUT = sys.argv[1] if len(sys.argv) > 1 else "assets/shade_src"

meta = {"bbox_with_margin": [S, W, N, E]}
t0 = time.time()
with rasterio.open(BLD) as r:
    l, b, rr, t = transform_bounds("EPSG:4326", r.crs, W, S, E, N)
    win = from_bounds(l, b, rr, t, r.transform).round_offsets().round_lengths()
    h, w = int(win.height) // 4, int(win.width) // 4          # 0.5 m -> 2 m (the data's effective resolution is about 4 m)
    height = r.read(2, window=win, out_shape=(h, w), resampling=rasterio.enums.Resampling.average)
    pres = r.read(3, window=win, out_shape=(h, w), resampling=rasterio.enums.Resampling.average)
    tr = r.window_transform(win)
    meta["buildings"] = {"crs": str(r.crs), "origin": [tr.c, tr.f], "pixel_m": [tr.a * 4, tr.e * 4], "shape": [h, w]}
    np.save(f"{OUT}/bld_height.npy", height.astype("float32")); np.save(f"{OUT}/bld_presence.npy", pres.astype("float32"))
print("buildings", height.shape, "height max %.1f presence>0.5 share %.3f" % (np.nanmax(height), (pres > 0.5).mean()), "%.0fs" % (time.time() - t0))
t0 = time.time()
with rasterio.open(CHM) as r:
    l, b, rr, t = transform_bounds("EPSG:4326", r.crs, W, S, E, N)
    win = from_bounds(l, b, rr, t, r.transform).round_offsets().round_lengths()
    chm = r.read(1, window=win)
    tr = r.window_transform(win)
    meta["canopy"] = {"crs": str(r.crs), "origin": [tr.c, tr.f], "pixel_m": [tr.a, tr.e], "shape": list(chm.shape)}
    np.save(f"{OUT}/canopy.npy", chm.astype("uint8"))
print("canopy", chm.shape, "max %d m, >2 m share %.3f" % (chm.max(), (chm > 2).mean()), "%.0fs" % (time.time() - t0))
json.dump(meta, open(f"{OUT}/window.json", "w"), indent=1)
