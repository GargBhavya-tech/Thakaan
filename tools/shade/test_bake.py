"""Checks for the shade bake maths: sun position against known values, and a wall's shadow against geometry."""
import math, sys
import numpy as np
sys.path.insert(0, "tools/shade")
import bake_shade as b


def test_sun_matches_known_values():
    el, az = b.sun(172, 12 * 60 + 5)          # 21 June, near solar noon in Bengaluru: sun high, slightly north of overhead
    assert 76 < el < 80 and (az < 60 or az > 300)
    el, az = b.sun(355, 12 * 60 + 5)          # 21 December: elevation about 90 - 13 - 23.4
    assert 52 < el < 55 and 165 < az < 185
    assert b.sun(172, 5 * 60)[0] < 2          # before sunrise


def test_wall_throws_the_geometric_shadow():
    res = 2.0
    H = np.zeros((400, 400), np.float32)
    H[:, 200:203] = 20.0                      # a north-south wall 20 m high
    el, az = 45.0, 270.0                      # sun due west: the wall's shadow falls east, 20 m long at 45 degrees
    col = np.array([205.0, 210.0, 216.0, 150.0]); row = np.full(4, 200.0)
    # samples 6 m, 16 m and 28 m east of the wall (shadow is 20 m long) and one 100 m west of it; the march goes towards the sun (west)
    blocked = b.march(col, row, H, res, el, az)
    assert blocked[0] and blocked[1] and not blocked[2] and not blocked[3]


def test_low_sun_reaches_further_than_high_sun():
    res = 2.0; H = np.zeros((600, 600), np.float32); H[:, 100] = 30.0
    col = np.array([140.0]); row = np.array([300.0])   # 80 m east of the wall
    assert b.march(col, row, H, res, 15.0, 270.0)[0]   # 30 m wall at 15 degrees throws 112 m
    assert not b.march(col, row, H, res, 60.0, 270.0)[0]
