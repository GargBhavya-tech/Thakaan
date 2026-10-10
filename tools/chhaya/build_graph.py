"""Build the offline Chhaya Route graph from the staged OSM tiles (ticket #46).

Run:  python -I tools/chhaya/build_graph.py            (from the repo root)
Reads assets/osm/tile_*.osm (ODbL, (c) OpenStreetMap contributors), writes assets/chhaya/graph.json.

Graph schema (all coordinates degrees, geometry stored as integers in 1e-6 degrees):
  bbox      [south, west, north, east] of the demo area
  nodes     [[lat, lon], ...]                       intersections / road ends
  edges     [[u, v, length_m, speed_kmh, oneway, [lat1e6, lon1e6, ...]], ...]
            oneway: 0 both ways, 1 only u->v, -1 only v->u
  pois      [{t, lat, lon, name, node, acc_m}]       t in water|tea|shop|fuel|shelter|seat|hospital
Water points are "listed, quality unverified": OSM cannot vouch for water quality.
"""
import glob
import json
import math
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BBOX = (12.960, 77.585, 12.990, 77.615)  # south, west, north, east (the demo area)
MARGIN = 0.002

SPEED = {
    "trunk": 35, "trunk_link": 25, "primary": 30, "primary_link": 22, "secondary": 28, "secondary_link": 20,
    "tertiary": 25, "tertiary_link": 18, "unclassified": 22, "residential": 20, "living_street": 10, "service": 12,
}

POI_RULES = [
    (("amenity", "drinking_water"), "water"), (("amenity", "water_point"), "water"), (("man_made", "water_tap"), "water"),
    (("amenity", "shelter"), "shelter"), (("amenity", "bench"), "seat"), (("amenity", "cafe"), "tea"),
    (("shop", "tea"), "tea"), (("shop", "convenience"), "shop"), (("shop", "kiosk"), "shop"), (("shop", "general"), "shop"),
    (("amenity", "fuel"), "fuel"), (("amenity", "hospital"), "hospital"), (("amenity", "clinic"), "hospital"),
]


def hav(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dphi = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def inside(lat, lon, m=0.0):
    return BBOX[0] - m <= lat <= BBOX[2] + m and BBOX[1] - m <= lon <= BBOX[3] + m


def main():
    nodes = {}
    ways = []
    poi_nodes = []
    for path in sorted(glob.glob(os.path.join(ROOT, "assets", "osm", "tile_*.osm"))):
        print("reading", os.path.basename(path), file=sys.stderr)
        cur_way = None
        cur_node = None
        for ev, el in ET.iterparse(path, events=("start", "end")):
            if ev == "start":
                if el.tag == "node":
                    cur_node = {"id": int(el.get("id")), "lat": float(el.get("lat")), "lon": float(el.get("lon")), "tags": {}}
                elif el.tag == "way":
                    cur_way = {"id": int(el.get("id")), "refs": [], "tags": {}}
                elif el.tag == "nd" and cur_way is not None:
                    cur_way["refs"].append(int(el.get("ref")))
                elif el.tag == "tag":
                    tgt = cur_node if cur_node is not None else cur_way
                    if tgt is not None:
                        tgt["tags"][el.get("k")] = el.get("v")
            else:
                if el.tag == "node" and cur_node is not None:
                    if inside(cur_node["lat"], cur_node["lon"], MARGIN):
                        nodes[cur_node["id"]] = (cur_node["lat"], cur_node["lon"])
                        if cur_node["tags"]:
                            poi_nodes.append(cur_node)
                    cur_node = None
                elif el.tag == "way" and cur_way is not None:
                    if cur_way["tags"].get("highway") in SPEED:
                        ways.append(cur_way)
                    cur_way = None
                if el.tag in ("node", "way", "relation"):
                    el.clear()

    # drop ways with no node in the area, and de-duplicate ways seen in two tiles
    seen = set()
    use = []
    for w in ways:
        if w["id"] in seen:
            continue
        seen.add(w["id"])
        pts = [nodes[r] for r in w["refs"] if r in nodes]
        if any(inside(p[0], p[1]) for p in pts):
            w["refs"] = [r for r in w["refs"] if r in nodes]
            if len(w["refs"]) >= 2:
                use.append(w)
    print("ways in area:", len(use), file=sys.stderr)

    count = defaultdict(int)
    for w in use:
        for r in w["refs"]:
            count[r] += 1
        count[w["refs"][0]] += 1
        count[w["refs"][-1]] += 1
    key = {r for r, c in count.items() if c >= 2}

    node_index = {}
    out_nodes = []

    def nid(r):
        if r not in node_index:
            node_index[r] = len(out_nodes)
            out_nodes.append([round(nodes[r][0], 6), round(nodes[r][1], 6)])
        return node_index[r]

    edges = []
    for w in use:
        tags = w["tags"]
        ow = tags.get("oneway", "")
        if tags.get("junction") == "roundabout":
            ow = "yes"
        oneway = 1 if ow in ("yes", "1", "true") else (-1 if ow == "-1" else 0)
        speed = SPEED[tags["highway"]]
        seg = [w["refs"][0]]
        for r in w["refs"][1:]:
            seg.append(r)
            if r in key or r == w["refs"][-1]:
                if len(seg) >= 2 and seg[0] != seg[-1]:
                    geom = [nodes[x] for x in seg]
                    length = sum(hav(geom[i], geom[i + 1]) for i in range(len(geom) - 1))
                    flat = []
                    for p in geom:
                        flat += [int(round(p[0] * 1e6)), int(round(p[1] * 1e6))]
                    edges.append([nid(seg[0]), nid(seg[-1]), round(length, 1), speed, oneway, flat])
                seg = [r]

    # keep the largest connected component (treating edges as undirected)
    parent = list(range(len(out_nodes)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for e in edges:
        a, b = find(e[0]), find(e[1])
        if a != b:
            parent[a] = b
    sizes = defaultdict(int)
    for i in range(len(out_nodes)):
        sizes[find(i)] += 1
    big = max(sizes, key=sizes.get)
    remap = {}
    final_nodes = []
    for i, n in enumerate(out_nodes):
        if find(i) == big:
            remap[i] = len(final_nodes)
            final_nodes.append(n)
    final_edges = [[remap[e[0]], remap[e[1]], e[2], e[3], e[4], e[5]] for e in edges if e[0] in remap and e[1] in remap]

    # points of interest snapped to the nearest graph node
    pois = []
    for n in poi_nodes:
        if not inside(n["lat"], n["lon"]):
            continue
        kind = None
        for (k, v), t in POI_RULES:
            if n["tags"].get(k) == v:
                kind = t
                break
        if kind is None:
            continue
        best, bd = None, 1e9
        for i, g in enumerate(final_nodes):
            if abs(g[0] - n["lat"]) > 0.001 or abs(g[1] - n["lon"]) > 0.001:
                continue
            d = hav((n["lat"], n["lon"]), g)
            if d < bd:
                best, bd = i, d
        if best is not None and bd <= 120:
            pois.append({"t": kind, "lat": round(n["lat"], 6), "lon": round(n["lon"], 6),
                         "name": n["tags"].get("name", ""), "node": best, "acc_m": round(bd, 1)})

    os.makedirs(os.path.join(ROOT, "assets", "chhaya"), exist_ok=True)
    out = {"bbox": list(BBOX), "attribution": "© OpenStreetMap contributors (ODbL)", "nodes": final_nodes, "edges": final_edges, "pois": pois}
    with open(os.path.join(ROOT, "assets", "chhaya", "graph.json"), "w", encoding="utf8") as f:
        json.dump(out, f, separators=(",", ":"), ensure_ascii=False)
    kinds = defaultdict(int)
    for p in pois:
        kinds[p["t"]] += 1
    print(f"nodes={len(final_nodes)} edges={len(final_edges)} pois={dict(kinds)}", file=sys.stderr)


if __name__ == "__main__":
    main()
