"""Computed shadow layer for the demo area (ticket #47).

Run:  python -I tools/chhaya/shade.py            (after build_graph.py)
For every road segment and every 30 minutes from 06:00 to 18:00 IST on the demo date, sample points along the segment and
ray-cast towards the sun against building footprints (with heights) and tree canopy. Writes assets/chhaya/shade.json:
  slots     minutes since midnight of each slot
  shade     per edge (same order as graph.json), one 0..100 shade percentage per slot
  lowconf   per edge, 1 when most of the shading (or nearby building mass) rests on guessed building heights
Missing building heights use 3 floors x 3.2 m and mark the segment low-confidence rather than sunny or shaded.
"""
import json
import math
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEMO_DAY_OF_YEAR = 283  # 10 October
LAT0, LON0 = 12.975, 77.600
M_LAT = 111_320.0
M_LON = 111_320.0 * math.cos(math.radians(LAT0))
SLOTS = list(range(6 * 60, 18 * 60 + 1, 30))
TREE_RADIUS, TREE_HEIGHT = 4.0, 7.0
GUESS_FLOORS, FLOOR_M = 3, 3.2
CELL = 30.0
MAX_SHADOW_M = 250.0


def to_xy(lat, lon):
    return ((lon - LON0) * M_LON, (lat - LAT0) * M_LAT)


def sun_position(minute_ist, day_of_year=DEMO_DAY_OF_YEAR, lat=LAT0, lon=LON0):
    """NOAA low-precision solar position; returns (azimuth_deg clockwise from north, elevation_deg)."""
    g = 2 * math.pi / 365 * (day_of_year - 1 + (minute_ist - 330) / 1440)  # IST = UTC+5:30
    eqtime = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g) - 0.014615 * math.cos(2 * g) - 0.040849 * math.sin(2 * g))
    decl = 0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g) + 0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g)
    tst = (minute_ist + eqtime + 4 * lon - 60 * 5.5) % 1440
    ha = math.radians(tst / 4 - 180)
    la = math.radians(lat)
    cz = math.sin(la) * math.sin(decl) + math.cos(la) * math.cos(decl) * math.cos(ha)
    zen = math.acos(max(-1, min(1, cz)))
    elev = 90 - math.degrees(zen)
    az = math.degrees(math.atan2(math.sin(ha), math.cos(ha) * math.sin(la) - math.tan(decl) * math.cos(la))) + 180
    return az % 360, elev


class World:
    def __init__(self):
        self.buildings = []  # (pts, height, guessed, minx, miny, maxx, maxy)
        self.trees = []      # (x, y)
        self.grid = defaultdict(list)
        self.tree_grid = defaultdict(list)

    def add_building(self, pts, height, guessed):
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        idx = len(self.buildings)
        self.buildings.append((pts, height, guessed, min(xs), min(ys), max(xs), max(ys)))
        for cx in range(int(min(xs) // CELL), int(max(xs) // CELL) + 1):
            for cy in range(int(min(ys) // CELL), int(max(ys) // CELL) + 1):
                self.grid[(cx, cy)].append(idx)

    def add_tree(self, x, y):
        self.trees.append((x, y))
        self.tree_grid[(int(x // CELL), int(y // CELL))].append(len(self.trees) - 1)


def seg_hit_distance(px, py, dx, dy, length, pts):
    """Smallest t in [0,length] where the ray p + t*(dx,dy) enters polygon pts, or None."""
    best = None
    n = len(pts)
    for i in range(n):
        ax, ay = pts[i]; bx, by = pts[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        den = dx * ey - dy * ex
        if abs(den) < 1e-12:
            continue
        wx, wy = ax - px, ay - py
        t = (wx * ey - wy * ex) / den
        u = (wx * dy - wy * dx) / den
        if 0 <= t <= length and 0 <= u <= 1:
            if best is None or t < best:
                best = t
    return best


def point_in_poly(x, y, pts):
    inside = False
    n = len(pts)
    j = n - 1
    for i in range(n):
        xi, yi = pts[i]; xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def classify(world, x, y, az, elev):
    """Returns (shaded, guessed_height_involved) for a ground point."""
    if elev <= 0.5:
        return True, False
    tan_e = math.tan(math.radians(elev))
    # unit vector pointing from the point towards the sun, in x=east, y=north
    dx, dy = math.sin(math.radians(az)), math.cos(math.radians(az))
    max_len = min(MAX_SHADOW_M, 80.0 / tan_e)  # tallest plausible building we care about is 80 m
    # trees: the crown at TREE_HEIGHT shades a ground point whose sun ray passes within TREE_RADIUS of the crown centre
    for cell in cells_along(x, y, dx, dy, min(max_len, TREE_HEIGHT / tan_e + TREE_RADIUS + 2)):
        for ti in world.tree_grid.get(cell, ()):
            tx, ty = world.trees[ti]
            rx, ry = tx - x, ty - y
            along = rx * dx + ry * dy
            if along < 0:
                continue
            perp = abs(rx * dy - ry * dx)
            height_at = along * tan_e  # ray height above ground at that distance
            if perp <= TREE_RADIUS and abs(height_at - TREE_HEIGHT) <= 3.0:
                return True, False
    seen = set()
    for cell in cells_along(x, y, dx, dy, max_len):
        for bi in world.grid.get(cell, ()):
            if bi in seen:
                continue
            seen.add(bi)
            pts, h, guessed, minx, miny, maxx, maxy = world.buildings[bi]
            if point_in_poly(x, y, pts):
                continue  # standing inside a footprint (data error): ignore this building
            t = seg_hit_distance(x, y, dx, dy, max_len, pts)
            if t is not None and h >= t * tan_e:
                return True, guessed
    return False, False


def cells_along(x, y, dx, dy, length):
    out = []
    t = 0.0
    last = None
    while t <= length + CELL:
        c = (int((x + dx * t) // CELL), int((y + dy * t) // CELL))
        if c != last:
            out.append(c)
            last = c
        t += CELL / 2
    return out


def load_world():
    w = World()
    nodes = {}
    ways = []
    root = os.path.join(ROOT, "assets", "osm")
    for fname in ("demo_buildings.osm", "demo_canopy.osm"):
        cur = None
        for ev, el in ET.iterparse(os.path.join(root, fname), events=("start", "end")):
            if ev == "start":
                if el.tag == "node":
                    cur = {"kind": "n", "id": int(el.get("id")), "lat": float(el.get("lat")), "lon": float(el.get("lon")), "tags": {}}
                elif el.tag == "way":
                    cur = {"kind": "w", "id": int(el.get("id")), "refs": [], "tags": {}}
                elif el.tag == "nd" and cur is not None:
                    cur["refs"].append(int(el.get("ref")))
                elif el.tag == "tag" and cur is not None:
                    cur["tags"][el.get("k")] = el.get("v")
            else:
                if el.tag == "node" and cur is not None:
                    nodes[cur["id"]] = to_xy(cur["lat"], cur["lon"])
                    if cur["tags"].get("natural") == "tree":
                        w.add_tree(*nodes[cur["id"]])
                    cur = None
                elif el.tag == "way" and cur is not None:
                    ways.append(cur)
                    cur = None
                if el.tag in ("node", "way"):
                    el.clear()
    guessed_count = 0
    for way in ways:
        tags = way["tags"]
        pts = [nodes[r] for r in way["refs"] if r in nodes]
        if len(pts) < 4:
            continue
        if "building" in tags:
            h, guessed = None, False
            try:
                if "height" in tags:
                    h = float(tags["height"].split()[0].replace("m", ""))
                elif "building:levels" in tags:
                    h = float(tags["building:levels"]) * FLOOR_M
            except ValueError:
                h = None
            if h is None:
                h, guessed = GUESS_FLOORS * FLOOR_M, True
                guessed_count += 1
            w.add_building(pts[:-1] if pts[0] == pts[-1] else pts, max(h, 2.5), guessed)
        elif tags.get("natural") == "wood" or tags.get("landuse") == "forest":
            # woodland: scatter virtual tree crowns on a 9 m grid inside the polygon
            xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
            gx = min(xs)
            while gx <= max(xs):
                gy = min(ys)
                while gy <= max(ys):
                    if point_in_poly(gx, gy, pts):
                        w.add_tree(gx, gy)
                    gy += 9.0
                gx += 9.0
    print(f"buildings={len(w.buildings)} (height guessed for {guessed_count}) trees={len(w.trees)}", file=sys.stderr)
    return w


def sample_edge(flat, step=15.0):
    pts = [to_xy(flat[i] / 1e6, flat[i + 1] / 1e6) for i in range(0, len(flat), 2)]
    out = [pts[0]]
    carry = 0.0
    for i in range(len(pts) - 1):
        (ax, ay), (bx, by) = pts[i], pts[i + 1]
        seg = math.hypot(bx - ax, by - ay)
        d = step - carry
        while d < seg:
            out.append((ax + (bx - ax) * d / seg, ay + (by - ay) * d / seg))
            d += step
        carry = seg - (d - step)
    out.append(pts[-1])
    return out


def main():
    graph = json.load(open(os.path.join(ROOT, "assets", "chhaya", "graph.json"), encoding="utf8"))
    world = load_world()
    suns = [sun_position(m) for m in SLOTS]
    shade, lowconf = [], []
    for ei, e in enumerate(graph["edges"]):
        samples = sample_edge(e[5])
        row = []
        guessed_hits = 0
        total_hits = 0
        for (az, elev) in suns:
            n_shaded = 0
            for (x, y) in samples:
                s, g = classify(world, x, y, az, elev)
                if s:
                    n_shaded += 1
                    total_hits += 1
                    if g:
                        guessed_hits += 1
            row.append(round(100 * n_shaded / len(samples)))
        shade.append(row)
        lowconf.append(1 if total_hits > 0 and guessed_hits / total_hits > 0.5 else 0)
        if ei % 500 == 0:
            print("edge", ei, "/", len(graph["edges"]), file=sys.stderr)
    out = {"slots": SLOTS, "day_of_year": DEMO_DAY_OF_YEAR, "shade": shade, "lowconf": lowconf}
    with open(os.path.join(ROOT, "assets", "chhaya", "shade.json"), "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print("done; low-confidence edges:", sum(lowconf), file=sys.stderr)


if __name__ == "__main__":
    main()
