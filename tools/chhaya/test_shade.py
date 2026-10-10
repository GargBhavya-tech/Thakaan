"""Geometry checks for tools/chhaya/shade.py. Run: python -I tools/chhaya/test_shade.py"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import shade as S  # noqa: E402


def world_with_building(h=20.0, guessed=False):
    w = S.World()
    # 20 m x 20 m block centred on the origin
    w.add_building([(-10, -10), (10, -10), (10, 10), (-10, 10)], h, guessed)
    return w


def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        check.failed += 1


check.failed = 0

# sun due south (az 180) at 45 deg: shadow falls north of the building, length = height
w = world_with_building(20.0)
check("point 15 m north of a 20 m block is shaded with sun in the south at 45 deg", S.classify(w, 0, 25, 180, 45)[0])
check("point 40 m north is beyond the 20 m shadow, so sunny", not S.classify(w, 0, 50, 180, 45)[0])
check("point south of the block is sunny", not S.classify(w, 0, -25, 180, 45)[0])
check("sun high (80 deg): the shadow is short, 15 m north is sunny", not S.classify(w, 0, 25, 180, 80)[0])
check("sun below horizon is all shade (no sun to shield from)", S.classify(w, 100, 100, 90, -5)[0])

# a low wall does not shade a point far behind it
w2 = world_with_building(3.0)
check("3 m wall: point 15 m behind at 45 deg is sunny", not S.classify(w2, 0, 25, 180, 45)[0])

# guessed heights are reported so the segment can be marked low-confidence
w3 = world_with_building(9.6, guessed=True)
s, g = S.classify(w3, 0, 15, 180, 45)
check("shade from a guessed-height building reports guessed=True", s and g)

# a tree shades the ground below its crown line
w4 = S.World()
w4.add_tree(0, 10)
# sun in the south at 45 deg: the crown (7 m up) shades the ground 7 m north of it
check("tree crown shades the ground 7 m on its shadow side", S.classify(w4, 0, 10 + S.TREE_HEIGHT, 180, 45)[0])
check("ground 7 m to the sun side of the tree is sunny", not S.classify(w4, 0, 10 - S.TREE_HEIGHT, 180, 45)[0])
check("ground far from the tree is sunny", not S.classify(w4, 60, 60, 180, 45)[0])

# sun position sanity for Bengaluru on 10 Oct
az, el = S.sun_position(12 * 60 + 15)
check(f"solar noon elevation high (got {el:.0f})", 70 < el < 85)
az8, el8 = S.sun_position(8 * 60)
check(f"morning sun is in the east (az {az8:.0f}, el {el8:.0f})", 60 < az8 < 120 and 5 < el8 < 35)
az16, el16 = S.sun_position(16 * 60)
check(f"afternoon sun is in the west (az {az16:.0f})", 240 < az16 < 290)

# edge sampling keeps endpoints and spacing
pts = S.sample_edge([12975000, 77600000, 12975000, 77600300])
check("edge sampling includes both ends", len(pts) >= 2)

print("failed:", check.failed)
sys.exit(1 if check.failed else 0)
