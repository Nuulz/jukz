"""Shoulder pets: rigged like animated.py pieces, in body-bone pixels (the left shoulder's top is y = 0, x 4..8).

    python3 pets.py [out_dir]     # one GIF per pet
"""
import math, os, sys
import numpy as np
from animated import Sculpt, Part, ellipsoid, model_json, gif

V = 0.25
O = (3.0, 1.0, -3.0)


def capsule(a, b, rad):
    def inside(x, y, z):
        ax, ay, az = a
        d = np.array(b) - np.array(a)
        t = max(0.0, min(1.0, float(np.dot(np.array([x - ax, y - ay, z - az]), d) / np.dot(d, d))))
        p = np.array(a) + t * d
        return (x - p[0]) ** 2 + (y - p[1]) ** 2 + (z - p[2]) ** 2 <= rad * rad
    return inside


def tube(s, points, rad, paint):
    for (p, q) in zip(points, points[1:]):
        lo = [min(p[i], q[i]) - rad - V for i in range(3)]
        hi = [max(p[i], q[i]) + rad + V for i in range(3)]
        inside = capsule(p, q, rad)
        s.fill(lo, hi, lambda x, y, z: paint(x, y, z) if inside(x, y, z) else None)


# ---- cat --------------------------------------------------------------------------------------

def cat():
    c, C, d, D, w, p, k, g, G = "c", "C", "d", "D", "w", "p", "k", "g", "G"

    def fur(x, y, z):
        if z > 0.9 and int(math.floor(-y / 0.5)) % 2 == 0:
            return d
        if abs(x - 6) > 1.3 and int(math.floor(-y / 0.5)) % 3 == 0:
            return d
        return c

    body = Sculpt(V, O)
    haunch = ellipsoid((6, -1.2, 0.6), (1.75, 1.3, 1.6))
    chest = ellipsoid((6, -2.4, -0.3), (1.25, 1.35, 1.05))
    body.fill((4, -4, -2), (8, 0, 2.5), lambda x, y, z: ((w if z < -0.6 and abs(x - 6) < 0.8 else fur(x, y, z)) if chest(x, y, z) or haunch(x, y, z) else None))
    for lx in (5.3, 6.7):
        tube(body, [(lx, -2.0, -0.85), (lx, -0.15, -1.0)], 0.4, lambda x, y, z: w if y > -0.55 else c)
        body.put(lx - 0.12, -0.12, -1.42, p)
    for hx in (4.55, 7.45):
        body.fill((hx - 0.6, -0.6, -0.9), (hx + 0.6, 0, 0.6), lambda x, y, z: w if z < -0.5 else c)

    hc, hr = (6, -4.35, -0.55), (1.6, 1.25, 1.25)
    head = Sculpt(V, O)

    def head_paint(x, y, z):
        if not ellipsoid(hc, hr)(x, y, z):
            return None
        if y < -5.0 and z < 0 and int(math.floor((x - 4) / 0.5)) % 2 == 0:
            return d
        return c
    head.fill((4, -6, -2), (8, -3, 1), head_paint)
    for cx in (5.0, 7.0):
        head.fill((cx - 0.7, -4.6, -1.6), (cx + 0.7, -3.4, -0.2), lambda x, y, z, cx=cx: (C if not head.cells.get(head.cell(x, y, z)) or z < -1.2 else None) if ellipsoid((cx, -3.95, -0.95), (0.65, 0.5, 0.65))(x, y, z) else None)
    head.fill((5.2, -4.5, -2.2), (6.8, -3.4, -1.2), lambda x, y, z: C if ellipsoid((6, -3.95, -1.6), (0.7, 0.45, 0.42))(x, y, z) else None)
    head.put(5.9, -4.3, -2.05, p)
    head.put(6.1, -4.3, -2.05, p)
    head.put(5.9, -3.85, -2.0, k)
    head.put(6.1, -3.85, -2.0, k)
    for side in (-1, 1):
        for i in range(3):
            head.put(6 + side * (1.25 + i * 0.25), -4.0 + (i - 1) * 0.05, -1.55, "W")

    eye_x, eye_y, eye_z = (5.35, 6.65), -4.7, -1.65

    def eyes(state):
        s = Sculpt(V, O)
        for ex in eye_x:
            for j in range(2):
                for i in range(2):
                    x, y = ex - 0.25 + i * V, eye_y + j * V
                    if state == "shut":
                        ch = k if j == 1 else c
                    else:
                        ch = k if (i == 1) == (ex > 6) else g
                        if j == 0 and (i == 0) == (ex > 6):
                            ch = "w"
                        if j == 1 and ch == g:
                            ch = G
                    s.put(x + 0.1, y + 0.1, eye_z, ch)
        return s
    for ex in eye_x:
        for j in range(2):
            for i in range(2):
                for dz in (0, V):
                    head.cells.pop(head.cell(ex - 0.25 + i * V + 0.1, eye_y + j * V + 0.1, eye_z + dz), None)

    def ear(cx, bend):
        s = Sculpt(V, O)
        for i, half in enumerate([0.7, 0.55, 0.4, 0.2]):
            y = -5.35 - i * V
            xo = cx + bend * i * 0.12
            for x in np.arange(xo - half, xo + half + 0.01, V):
                for z in (-0.75, -0.5, -0.25):
                    inner = z < -0.6 and abs(x - xo) < half - 0.2 and i < 3
                    s.put(x, y, z, p if inner else (D if i == 3 else c))
        return s

    def tail():
        s = Sculpt(V, O)
        pts = [(6.6, -0.5, 1.9), (7.4, -1.6, 2.4), (7.7, -3.0, 2.4), (7.4, -4.2, 2.1), (6.9, -4.8, 1.8)]
        tube(s, pts, 0.36, lambda x, y, z: D if y < -4.3 else (d if int(math.floor(-y / 0.6)) % 2 == 0 else c))
        return s

    parts = [
        Part("body", (6, 0, 0), [(1, body)], {"speed": 0.07, "move": [0, -0.06, 0], "run": {"speed": 0.3, "move": [0, -0.2, 0]}}),
        Part("head", (6, -3.4, -0.4), [(1, head)], {"speed": 0.025, "amp": [4, 22, 3], "run": {"speed": 0.2, "base": [-6, 0, 0], "amp": [3, 6, 0]}}, parent="body"),
        Part("eyes", (0, 0, 0), [(70, eyes("open")), (3, eyes("shut")), (40, eyes("open")), (3, eyes("shut")), (4, eyes("open")), (3, eyes("shut"))], parent="head"),
        Part("ear_l", (5.05, -5.3, -0.5), [(90, ear(5.05, 0)), (3, ear(5.05, -2)), (3, ear(5.05, 0)), (3, ear(5.05, -2))], parent="head"),
        Part("ear_r", (6.95, -5.3, -0.5), [(1, ear(6.95, 0))], {"speed": 0.025, "amp": [0, 0, 4]}, parent="head"),
        Part("tail", (6.6, -0.5, 1.9), [(1, tail())], {"speed": 0.08, "amp": [6, 0, 16], "run": {"speed": 0.35, "amp": [14, 0, 26]}}, parent="body"),
    ]
    return model_json(V, O, None, parts)


# ---- slime ------------------------------------------------------------------------------------

def slime():
    def jelly(wide, tall):
        s = Sculpt(V, O)
        hx, hz = wide / 2, wide / 2
        cx, cz = 6.0, 0.0

        def paint(x, y, z):
            if not (abs(x - cx) < hx and -tall < y < 0 and abs(z - cz) < hz):
                return None
            edge = (abs(x - cx) > hx - V) + (abs(z - cz) > hz - V) + (y < -tall + V or y > -V)
            if edge >= 2:
                return "h" if y < -tall / 2 else "e"
            core = abs(x - cx) < hx * 0.55 and abs(z - cz) < hz * 0.55 and -tall * 0.78 < y < -tall * 0.22
            if core:
                return "B"
            return "b"
        s.fill((cx - hx, -tall, cz - hz), (cx + hx, 0, cz + hz), paint)
        fz = cz - hz + V / 2
        ey = -tall * 0.62
        for ex in (cx - hx * 0.45, cx + hx * 0.45):
            for j in range(3):
                for i in range(2):
                    x, y = ex - 0.25 + i * V + 0.1, ey + j * V
                    s.put(x, y, fz, "w" if (j == 0 and i == (0 if ex < cx else 1)) else "k")
            s.put(ex + (-0.5 if ex < cx else 0.5) + 0.1, ey + 0.95, fz, "p")
        my = -tall * 0.3
        for i, dy in zip(range(-2, 2), (V, 0, 0, V)):
            s.put(cx + i * V + 0.12, my - dy, fz, "k")
        for (bx, by, bz) in ((cx + hx * 0.55, -tall * 0.8, cz + 0.3), (cx - hx * 0.6, -tall * 0.35, cz + hz * 0.5)):
            s.put(bx, by, bz, "W")
        return s

    frames = [(18, jelly(3.0, 3.0)), (3, jelly(3.5, 2.5)), (5, jelly(2.75, 3.4)), (3, jelly(3.0, 3.0)), (3, jelly(3.25, 2.75))]
    parts = [
        Part("jelly", (6, 0, 0), frames, {"speed": 0.1, "amp": [0, 6, 0], "run": {"speed": 0.35, "move": [0, -0.25, 0]}}),
    ]
    return model_json(V, O, None, parts)


PETS = [
    ("shoulder_cat", "Shoulder cat", "One of the six: blinks, looks around and swishes its tail.",
     {"c": "FFFFA94D", "C": "FFFFD7A3", "d": "FFD9772B", "D": "FFA8521A", "w": "FFFFFFFF", "W": "FFE8E8F0",
      "p": "FFFF8FB1", "k": "FF2A1A14", "g": "FF7BD86B", "G": "FF4FA845"},
     cat),
    ("jukz_slime", "jukz slime", "A wobbly blue slime that squishes on your shoulder.",
     {"b": "A05B9BFF", "e": "C08DBBFF", "h": "C03C78E0", "B": "F02F64C4", "w": "FFFFFFFF", "W": "B0FFFFFF",
      "k": "FF0B1A33", "p": "C0FF8FB1"},
     slime),
]


def build():
    return [{"id": iid, "kind": "pet", "name": name, "description": desc, "availability": "free",
             "palette": pal, "model": fn()} for (iid, name, desc, pal, fn) in PETS]


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    for it in build():
        path = os.path.join(out, f"{it['id']}.gif")
        gif(it, path)
        print(path)
