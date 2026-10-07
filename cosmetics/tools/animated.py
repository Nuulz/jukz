"""Animated 3D cosmetics: a still body plus parts that turn around a pivot, swap frames, or show only
while the wearer is still / moving / sneaking (the `rig` the mod reads). Pieces are sculpted in bone
pixels (y grows down, z grows towards the back; the body's back is z = 2) and voxelised at `voxel`.

    python3 animated.py [out_dir]     # one animated GIF per piece: idle, walking, sneaking

The preview builds the same quads and applies the same transforms as VoxelMesh.kt, so what moves here
moves the same way in game.
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw


class Sculpt:
    """A sparse voxel grid in bone pixels: cell (i, l, k) is x = ox + i*v, slice l going up, z = oz + k*v."""

    def __init__(self, voxel, origin):
        self.v = voxel
        self.ox, self.oy, self.oz = origin
        self.cells = {}

    def center(self, i, l, k):
        v = self.v
        return self.ox + (i + 0.5) * v, self.oy - (l + 0.5) * v, self.oz + (k + 0.5) * v

    def cell(self, x, y, z):
        v = self.v
        return math.floor((x - self.ox) / v), math.floor((self.oy - y) / v), math.floor((z - self.oz) / v)

    def fill(self, lo, hi, paint):
        """Calls paint(x, y, z) on each cell centre inside the bone-pixel box lo..hi; a returned char is set, None skips."""
        i0, l1, k0 = self.cell(*lo)
        i1, l0, k1 = self.cell(*hi)
        for i in range(i0, i1 + 1):
            for l in range(l0, l1 + 1):
                for k in range(k0, k1 + 1):
                    ch = paint(*self.center(i, l, k))
                    if ch == ".":
                        self.cells.pop((i, l, k), None)
                    elif ch:
                        self.cells[(i, l, k)] = ch

    def put(self, x, y, z, ch):
        self.cells[self.cell(x, y, z)] = ch

    def copy(self):
        s = Sculpt(self.v, (self.ox, self.oy, self.oz))
        s.cells = dict(self.cells)
        return s


def ellipsoid(c, r):
    return lambda x, y, z: ((x - c[0]) / r[0]) ** 2 + ((y - c[1]) / r[1]) ** 2 + ((z - c[2]) / r[2]) ** 2 <= 1


def seg_dist(p, a, b):
    ax, ay = a
    bx, by = b
    px, py = p
    dx, dy = bx - ax, by - ay
    t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy), t


def point_in_poly(p, poly):
    x, y = p
    inside = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


# ---- export --------------------------------------------------------------------------------------

def grid(sculpts, voxel, origin, bounds=None):
    """Slices for the union of sculpts, trimmed to their bounding box (or to bounds); returns (layers, origin, bounds)."""
    cells = {}
    for s in sculpts:
        cells.update(s.cells)
    if bounds is None:
        if not cells:
            return None, None, None
        xs, ls, ks = zip(*cells)
        bounds = (min(xs), max(xs), min(ls), max(ls), min(ks), max(ks))
    i0, i1, l0, l1, k0, k1 = bounds
    layers = [["".join(cells.get((i, l, k), ".") for i in range(i0, i1 + 1)) for k in range(k0, k1 + 1)] for l in range(l0, l1 + 1)]
    ox, oy, oz = origin
    return layers, [r(ox + i0 * voxel), r(oy - l0 * voxel), r(oz + k0 * voxel)], bounds


def r(f):
    return round(f, 4)


class Part:
    def __init__(self, name, pivot, frames, motion=None, parent=None, when=None):
        self.name, self.pivot, self.frames, self.motion, self.parent, self.when = name, pivot, frames, motion, parent, when


def model_json(voxel, origin, body, parts):
    """body: a Sculpt (or None); parts: [Part] with frames = [(ticks, Sculpt)]. The flat `layers` is the
    piece at rest (first frame of every part shown while standing still), for older mods and icons."""
    rest = [body] if body else []
    for p in parts:
        if p.when in (None, "still", "standing"):
            rest.append(p.frames[0][1])
    layers, o, _ = grid(rest, voxel, origin)
    rig = {"parts": []}
    if body and body.cells:
        rig["layers"], rig["origin"], _ = grid([body], voxel, origin)
    for p in parts:
        out = {"name": p.name, "pivot": [r(c) for c in p.pivot]}
        if p.parent:
            out["parent"] = p.parent
        if p.when:
            out["when"] = p.when
        # All frames share one box, so they line up.
        _, po, b = grid([s for _, s in p.frames], voxel, origin)
        if len(p.frames) == 1:
            out["origin"] = po
            out["layers"] = grid([p.frames[0][1]], voxel, origin, b)[0]
        else:
            out["origin"] = po
            out["frames"] = [{"ticks": t, "layers": grid([s], voxel, origin, b)[0]} for t, s in p.frames]
        if p.motion:
            out["motion"] = p.motion
        rig["parts"].append(out)
    model = {"voxel": voxel, "origin": o, "layers": layers, "rig": rig}
    return model


# ---- preview (mirrors CosmeticCatalog.quads + VoxelMesh) ------------------------------------------

def quads(layers, voxel, origin, palette):
    W, D = len(layers[0][0]), len(layers[0])
    ox, oy, oz = origin

    def at(x, l, z):
        return layers[l][z][x] if 0 <= l < len(layers) and 0 <= z < D and 0 <= x < W else "."

    out = []
    for l in range(len(layers)):
        for z in range(D):
            for x in range(W):
                ch = at(x, l, z)
                if ch == ".":
                    continue
                col = palette[ch]

                def open_(nx, nl, nz):
                    o = at(nx, nl, nz)
                    return o == "." or (o != ch and palette[o][3] < 255)

                x0, x1 = ox + x * voxel, ox + (x + 1) * voxel
                y1 = oy - l * voxel
                y0 = y1 - voxel
                z0, z1 = oz + z * voxel, oz + (z + 1) * voxel
                if open_(x, l + 1, z): out.append(([(x0, y0, z0), (x0, y0, z1), (x1, y0, z1), (x1, y0, z0)], (0, -1, 0), col))
                if open_(x, l - 1, z): out.append(([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), col))
                if open_(x, l, z - 1): out.append(([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), col))
                if open_(x, l, z + 1): out.append(([(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], (0, 0, 1), col))
                if open_(x - 1, l, z): out.append(([(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], (-1, 0, 0), col))
                if open_(x + 1, l, z): out.append(([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (1, 0, 0), col))
    return out


def argb(h):
    return (int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16), int(h[0:2], 16))


def rot_zyx(ax, ay, az):
    ax, ay, az = (math.radians(a) for a in (ax, ay, az))
    cx, sx, cy, sy, cz, sz = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay), math.cos(az), math.sin(az)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx  # JOML rotateZYX / Blockbench


def rot_xyz(ax, ay, az):
    ax, ay, az = (math.radians(a) for a in (ax, ay, az))
    cx, sx, cy, sy, cz, sz = math.cos(ax), math.sin(ax), math.cos(ay), math.sin(ay), math.cos(az), math.sin(az)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rx @ Ry @ Rz  # JOML rotateXYZ


def affine(R, t):
    m = np.eye(4)
    m[:3, :3] = R
    m[:3, 3] = t
    return m


def motion_of(mot, state):
    if state == "sneak" and "sneak" in mot:
        m = dict(mot); m.update(mot["sneak"]); return m
    if state == "run" and "run" in mot:
        m = dict(mot); m.update(mot["run"]); return m
    return mot


def part_matrix(part, ticks, state):
    mot = part.get("motion")
    if not mot:
        return np.eye(4)
    m = motion_of(mot, state)
    wave = math.sin(ticks * m.get("speed", 0) + m.get("phase", 0))
    base, amp, move = m.get("base", [0, 0, 0]), m.get("amp", [0, 0, 0]), m.get("move", [0, 0, 0])
    rot = [base[a] + amp[a] * wave for a in range(3)]
    p = np.array(part["pivot"], float)
    mv = np.array([move[a] * wave for a in range(3)])
    R = rot_zyx(*rot) if part.get("order") == "zyx" else rot_xyz(*rot)
    return affine(np.eye(3), p + mv) @ affine(R, [0, 0, 0]) @ affine(np.eye(3), -p)


def shown(when, state):
    moving, sneaking = state == "run", state == "sneak"
    return {None: True, "still": not moving, "moving": moving, "sneaking": sneaking, "standing": not sneaking}.get(when, False)


def frame_layers(part, ticks):
    if "frames" not in part:
        return part["layers"]
    total = sum(f["ticks"] for f in part["frames"])
    t = int(ticks % total)
    for f in part["frames"]:
        if t < f["ticks"]:
            return f["layers"]
        t -= f["ticks"]
    return part["frames"][-1]["layers"]


def posed_quads(item, ticks, state):
    model, pal = item["model"], {k: argb(v) for k, v in item["palette"].items()}
    v = model["voxel"]
    rig = model.get("rig")
    if not rig:
        return quads(model["layers"], v, model["origin"], pal)
    out = []
    if "layers" in rig:
        out += quads(rig["layers"], v, rig["origin"], pal)
    mats = {}
    for part in rig["parts"]:
        m = part_matrix(part, ticks, state)
        if "parent" in part:
            m = mats[part["parent"]] @ m
        mats[part["name"]] = m
        if not shown(part.get("when"), state):
            continue
        for corners, n, col in quads(frame_layers(part, ticks), v, part["origin"], pal):
            c = [tuple((m @ np.array([*p, 1.0]))[:3]) for p in corners]
            out.append((c, tuple(m[:3, :3] @ np.array(n, float)), col))
    return out


def box(x0, y0, z0, x1, y1, z1, col):
    """A box as 1-pixel tiles, so the painter's sort stays right next to the small voxel faces."""
    out = []

    def steps(a, b):
        n = max(1, round(b - a))
        return [a + (b - a) * i / n for i in range(n + 1)]
    xs, ys, zs = steps(x0, x1), steps(y0, y1), steps(z0, z1)
    for a, b in zip(xs, xs[1:]):
        for c, d in zip(ys, ys[1:]):
            out.append(([(a, c, z0), (b, c, z0), (b, d, z0), (a, d, z0)], (0, 0, -1), col))
            out.append(([(b, c, z1), (a, c, z1), (a, d, z1), (b, d, z1)], (0, 0, 1), col))
    for c, d in zip(ys, ys[1:]):
        for e, f in zip(zs, zs[1:]):
            out.append(([(x0, c, f), (x0, c, e), (x0, d, e), (x0, d, f)], (-1, 0, 0), col))
            out.append(([(x1, c, e), (x1, c, f), (x1, d, f), (x1, d, e)], (1, 0, 0), col))
    for a, b in zip(xs, xs[1:]):
        for e, f in zip(zs, zs[1:]):
            out.append(([(a, y0, e), (a, y0, f), (b, y0, f), (b, y0, e)], (0, -1, 0), col))
            out.append(([(a, y1, e), (b, y1, e), (b, y1, f), (a, y1, f)], (0, 1, 0), col))
    return out


SKIN, SHIRT, PANTS = (214, 166, 128, 255), (60, 160, 160, 255), (60, 70, 160, 255)


def player(state):
    """A reference Steve in body-bone pixels; sneaking leans the body (and what's on it) forward."""
    head = box(-4, -8, -4, 4, 0, 4, SKIN) + box(-1.5, -4.5, -4.05, -0.5, -3.5, -4.0, (40, 40, 60, 255)) + box(0.5, -4.5, -4.05, 1.5, -3.5, -4.0, (40, 40, 60, 255))
    body = box(-4, 0, -2, 4, 12, 2, SHIRT) + box(-8, 0, -2, -4, 12, 2, SKIN) + box(4, 0, -2, 8, 12, 2, SKIN)
    legs = box(-4, 12, -2, 0, 24, 2, PANTS) + box(0, 12, -2, 4, 24, 2, PANTS)
    return head, body, legs


def transform(qs, m):
    return [([tuple((m @ np.array([*p, 1.0]))[:3]) for p in c], tuple(m[:3, :3] @ np.array(n, float)), col) for c, n, col in qs]


def render(item, ticks, state, yaw, size=360, scale=9.0, cy=0.40):
    head, body, legs = player(state)
    piece = posed_quads(item, ticks, state)
    lean = affine(rot_xyz(28.6 if state == "sneak" else 0, 0, 0), [0, 0, 0])
    on_body = item["kind"] == "back"
    qs = head + transform(body, lean) + legs + (transform(piece, lean) if on_body else piece)
    # camera: turn the world by yaw around y, tilt it a little, look along +z of the camera (orthographic).
    view = rot_xyz(-12, 0, 0) @ rot_xyz(0, yaw, 0)
    light = np.array([0.35, -0.8, -0.5]); light /= np.linalg.norm(light)
    img = Image.new("RGBA", (size, size), (24, 28, 40, 255))
    d = ImageDraw.Draw(img, "RGBA")
    drawn = []
    for c, n, col in qs:
        pts = [view @ np.array(p) for p in c]
        nn = view @ np.array(n)
        if nn[2] > 1e-6:  # facing away (camera looks towards -z in view space)
            continue
        depth = sum(p[2] for p in pts) / 4
        drawn.append((depth, pts, nn, col))
    drawn.sort(key=lambda t: -t[0])
    for depth, pts, nn, col in drawn:
        f = 0.5 + 0.5 * max(0.0, float(np.dot(nn, -light)))
        fill = tuple(int(c * f) for c in col[:3]) + (col[3],)
        poly = [(size / 2 + p[0] * scale, size * cy + p[1] * scale) for p in pts]
        d.polygon(poly, fill=fill)
    return img


def gif(item, path):
    frames = []
    states = [("still", "idle", 100), ("run", "run", 80), ("sneak", "sneak", 60)]
    t = 0
    for state, label, length in states:
        for _ in range(0, length, 2):
            a = render(item, t, state, 180 + 35)
            b = render(item, t, state, 35)
            sheet = Image.new("RGBA", (a.width * 2, a.height), (24, 28, 40, 255))
            sheet.paste(a, (0, 0)); sheet.paste(b, (a.width, 0))
            ImageDraw.Draw(sheet).text((10, 10), f"{item['name']} - {label}", fill="white")
            frames.append(sheet.convert("RGB"))
            t += 2
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=100, loop=0)


def web_gif(item, path, yaw, size=320, scale=9.0, labels=("still", "walking", "sneaking"), cy=0.40):
    """One view, for a web page: still, walking and sneaking in a loop, a small label in the corner."""
    frames = []
    t = 0
    for (state, length), label in zip((("still", 100), ("run", 60), ("sneak", 40)), labels):
        for _ in range(0, length, 2):
            im = render(item, t, state, yaw, size=size, scale=scale, cy=cy).convert("RGB")
            ImageDraw.Draw(im).text((10, 8), label, fill=(165, 173, 203))
            frames.append(im)
            t += 2
    # one palette for the whole loop, so colours don't shift between frames
    strip = Image.new("RGB", (size, size * 4))
    for i, k in enumerate((0, 50, 70, 90)):
        strip.paste(frames[min(k, len(frames) - 1)], (0, i * size))
    pal = strip.quantize(colors=96, method=Image.Quantize.MEDIANCUT)
    out = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    out[0].save(path, save_all=True, append_images=out[1:], duration=100, loop=0, optimize=True)


# ---- pieces --------------------------------------------------------------------------------------

def dragon_wings():
    V = 0.5
    O = (-17.5, 13.0, 2.25)  # grid corner: bottom-left-front of the whole piece
    # One wing drawn flat in (u = out from the root, w = down from the top) half-pixels, then placed
    # on each side. The arm runs along the top, fingers fan down to clawed tips, membrane between.
    root_top, elbow, wrist = (0, 6), (8, -2), (15, -8)
    tips = [(31, -3), (29, 8), (23, 17), (13, 23)]
    root_bottom = (0, 16)
    poly = [root_top, elbow, wrist] + tips + [root_bottom]
    bones = [(root_top, elbow, 1.4), (elbow, wrist, 1.2)] + [(wrist, t, 0.75) for t in tips]
    edges = list(zip(tips, tips[1:])) + [(tips[-1], root_bottom)]

    def scallop(p):
        for a, b in edges:
            mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
            dx, dy = mx - wrist[0], my - wrist[1]
            L = math.hypot(dx, dy)
            ln = math.hypot(b[0] - a[0], b[1] - a[1])
            rr = ln * 0.5
            cx, cy = mx + dx / L * rr * 0.72, my + dy / L * rr * 0.72
            if math.hypot(p[0] - cx, p[1] - cy) < rr:
                return True
        return False

    def wing_cell(u, w, k):
        """What sits at half-pixel (u, w), depth k (0 = against the back, 1 = ribs that stand out)."""
        p = (u, w)
        on_bone = None
        for a, b, th in bones:
            dist, t = seg_dist(p, a, b)
            if dist <= th * (1 - 0.35 * t if a == wrist else 1):
                on_bone = (a, b, t)
                break
        if on_bone:
            a, b, t = on_bone
            if a == wrist and t > 0.86:
                return "c"  # claws on the finger tips
            if k == 1 and a != wrist and seg_dist((u, w + 1), a, b)[0] > 1.2:
                return "h"  # top of the arm catches the light
            return "b"
        if k == 1:
            return None
        # the wrist claw, hooked up and out
        if (u, w) in ((15, -9), (15, -10), (16, -11), (17, -11), (14, -9)):
            return "c"
        if not point_in_poly(p, poly) or scallop(p):
            return None
        # membrane: darker near the arm and root, lighter and glowing to the edge
        edge = min(seg_dist(p, a, b)[0] for a, b in edges)
        if edge < 1.0:
            return "r"
        return "M" if edge < 3.5 else "m"

    def wing(side, outer):
        s = Sculpt(V, O)
        sign = -1 if side == "l" else 1
        for k in range(2):
            for u in range(-1, 34):
                if (u >= 10) != outer:
                    continue
                for w in range(-13, 28):
                    ch = wing_cell(u, w, k)
                    if ch:
                        x = sign * (1.5 + (u + 0.5) * V) - (V if sign < 0 else 0)
                        y = -3.0 + w * V
                        z = 2.25 + k * V
                        s.put(x + V / 2, y + V / 2, z + V / 2, ch)
        return s

    def mirror(m, flip):
        # Mirrored wing turns the other way around y and z.
        out = dict(m)
        for key in ("base", "amp", "move"):
            if key in out:
                out[key] = [out[key][0], out[key][1] * flip, out[key][2] * flip]
        for st in ("run", "sneak"):
            if st in out:
                out[st] = mirror(out[st], flip)
        return out

    inner = {"speed": 0.07, "base": [0, 28, 0], "amp": [0, 4, 6],
             "run": {"speed": 0.5, "base": [0, 12, 4], "amp": [0, 6, 26]},
             "sneak": {"speed": 0.05, "base": [0, 72, -6], "amp": [0, 2, 1]}}
    outer = {"speed": 0.07, "phase": -0.8, "base": [0, 14, 0], "amp": [0, 6, 4],
             "run": {"speed": 0.5, "phase": -1.1, "base": [0, 6, 0], "amp": [0, 4, 22]},
             "sneak": {"speed": 0.05, "base": [0, 80, 0], "amp": [0, 0, 0]}}
    parts = []
    for side, flip, x in (("l", 1, -1.5), ("r", -1, 1.5)):
        elbow_x = x + (-1 if side == "l" else 1) * 5.0
        parts.append(Part(f"wing_{side}", (x, 0.5, 2.5), [(1, wing(side, False))], mirror(inner, flip)))
        parts.append(Part(f"wing_{side}_tip", (elbow_x, -4.0, 2.5), [(1, wing(side, True))], mirror(outer, flip), parent=f"wing_{side}"))
    # a little spine plate between the wings, where they join
    body = Sculpt(V, O)
    body.fill((-1.5, -0.5, 2.0), (1.5, 6.0, 3.0), lambda x, y, z: ("s" if z < 2.5 else ("S" if int((y + 0.5) / V) % 3 == 0 else "s")) if abs(x) < 1.6 - 0.12 * max(0, y) else None)
    return model_json(V, O, body, parts)


def sleeping_cat():
    V = 0.5
    O = (-8.0, 14.0, 1.75)
    c, C, d, D, p, w, k, g, n = "c", "C", "d", "D", "p", "w", "k", "g", "n"

    def fur(x, y, z, stripes=True):
        if stripes and int(math.floor(x / 1.5)) % 2 == 0 and y < 2.6:
            return d
        return c

    # torso: a loaf across the upper back, belly on the player, a bit lower on the left
    torso = Sculpt(V, O)
    tc, tr = (-0.5, 1.6, 4.3), (4.6, 2.1, 2.2)
    torso.fill((-5.5, -1, 2.0), (4.5, 4.2, 7.0), lambda x, y, z: (C if y > 2.9 else fur(x, y, z)) if ellipsoid(tc, tr)(x, y, z) else None)
    # hind leg tucked on the left, white sock
    torso.fill((-5.5, 1.5, 3.0), (-2.5, 4.5, 6.6), lambda x, y, z: (w if y > 3.6 else c) if ellipsoid((-4.0, 3.0, 4.8), (1.5, 1.4, 1.6))(x, y, z) else None)
    # front paws draped over the right shoulder, pink beans at the tips
    torso.fill((2.5, -0.5, 1.6), (7.5, 2.5, 4.0), lambda x, y, z: ((p if x > 6.4 and y > 0.8 else w) if x > 5.6 else c) if (abs(z - 2.6) < 0.9 and ((abs(y - 0.6) < 0.8 and abs(x - 5.5) < 1.9))) else None)

    # head on the right shoulder, face to the back (so whoever walks behind sees it sleep)
    hc, hr = (5.2, -1.2, 3.8), (2.3, 2.0, 2.0)
    head = Sculpt(V, O)

    def head_paint(x, y, z):
        if not ellipsoid(hc, hr)(x, y, z):
            return None
        if z > 5.2 and abs(x - hc[0]) < 0.8 and -1.0 < y < 0.0:
            return C  # muzzle
        if y < -2.2 and int(math.floor(x / 1.0)) % 2 == 0:
            return d
        return c
    head.fill((2.5, -4, 1.5), (8, 1.5, 6.5), head_paint)
    head.put(5.2, -0.6, 5.85, p)  # nose
    head.put(5.2, -0.1, 5.85, C)
    # cheeks puff out a little
    head.fill((2.6, -1.5, 3.0), (7.9, 0.5, 5.6), lambda x, y, z: (C if z > 4.8 else c) if (ellipsoid((3.4, -0.6, 4.2), (0.9, 0.8, 1.2))(x, y, z) or ellipsoid((7.0, -0.6, 4.2), (0.9, 0.8, 1.2))(x, y, z)) and not head.cells.get(head.cell(x, y, z)) else None)

    eye_cells = [(4.1, -1.3), (6.3, -1.3)]  # x, y; on the back of the face (z ~ 5.6)
    EYE, EYE_Y = (-0.5, 0.0, 0.5), (-0.5, 0.0)
    eyez = 5.6

    def eyes(left, right):
        """left/right: 'shut' (a sleepy line), 'open' (green with a slit)."""
        s = Sculpt(V, O)
        shut = ["k.k", ".k."]  # a sleepy curve, rows top -> bottom
        awake = ["gkg", "gkg"]  # green with a slit pupil
        for (ex, ey), st in zip(eye_cells, (left, right)):
            for j, row in enumerate(shut if st == "shut" else awake):
                for i, ch in enumerate(row):
                    s.put(ex + EYE[i] + 0.25, ey + EYE_Y[j] + 0.25, eyez + 0.25, c if ch == "." else ch)
        return s

    # carve the eye sockets out of the head so the eye parts fill them
    for (ex, ey) in eye_cells:
        for dx in EYE:
            for dy in EYE_Y:
                head.cells.pop(head.cell(ex + dx + 0.25, ey + dy + 0.25, eyez + 0.25), None)

    def ear(cx, bend):
        s = Sculpt(V, O)
        for i, half in enumerate([1.2, 0.9, 0.5, 0.2]):
            y = -3.0 - i * 0.5
            xo = cx + (bend * i * 0.35)
            for x in np.arange(xo - half, xo + half + 0.01, V):
                for z in (3.6, 4.1):
                    s.put(x, y, z, p if (z > 4 and abs(x - xo) < half - 0.3 and i < 2) else (D if i == 3 else c))
        return s

    tail_base = (-4.9, 2.2, 4.6)

    def tail(seg):
        s = Sculpt(V, O)
        if seg == 0:  # from the rump down the left side of the back
            for t in np.arange(0, 1.0001, 0.05):
                x, y, z = -4.9 - 0.8 * t, 2.2 + 4.0 * t, 4.6 - 0.6 * t
                s.fill((x - 1, y - 1, z - 1), (x + 1, y + 1, z + 1), lambda X, Y, Z: (d if int(Y / 1.0) % 2 == 0 else c) if ellipsoid((x, y, z), (0.75, 0.75, 0.75))(X, Y, Z) else None)
        else:  # the hanging tip, curling out at the end, dark tip
            for t in np.arange(0, 1.0001, 0.05):
                x, y, z = -5.7 - 0.6 * math.sin(t * 2.4), 6.2 + 3.4 * t, 4.0 + 0.3 * t
                s.fill((x - 1, y - 1, z - 1), (x + 1, y + 1, z + 1), lambda X, Y, Z: (D if t > 0.8 else (d if int(Y / 1.0) % 2 == 0 else c)) if ellipsoid((x, y, z), (0.7, 0.7, 0.7))(X, Y, Z) else None)
        return s

    def zzz(n_letters):
        s = Sculpt(V, O)
        letters = [((8.0, -4.2), 4), ((9.4, -7.0), 5), ((11.0, -10.4), 6)]
        for (lx, ly), size in letters[:n_letters]:
            for j in range(size):
                for i in range(size):
                    if j == 0 or j == size - 1 or i == j:  # mirrored: it's read from behind
                        s.put(lx + i * V + 0.25, ly + j * V + 0.25, 5.25, "z")
        return s

    parts = [
        Part("torso", (0, 4.0, 2.2), [(1, torso)], {"speed": 0.09, "move": [0, -0.18, 0.12], "run": {"speed": 0.35, "move": [0, -0.3, 0]}}),
        Part("head", (4.0, 0.2, 3.2), [(1, head)], {"speed": 0.09, "phase": -0.6, "amp": [3, 0, 0], "run": {"speed": 0.3, "base": [0, -14, 6], "amp": [0, 6, 2]}}, parent="torso"),
        Part("eyes_sleep", (0, 0, 0), [(170, eyes("shut", "shut")), (6, eyes("shut", "open")), (24, eyes("shut", "open")), (40, eyes("shut", "shut"))], parent="head", when="still"),
        Part("eyes_awake", (0, 0, 0), [(80, eyes("open", "open")), (4, eyes("shut", "shut"))], parent="head", when="moving"),
        Part("ear_l", (3.9, -2.8, 3.8), [(110, ear(3.9, 0)), (3, ear(3.9, -1.4)), (3, ear(3.9, 0)), (3, ear(3.9, -1.4))], parent="head"),
        Part("ear_r", (6.5, -2.8, 3.8), [(1, ear(6.5, 0))], {"speed": 0.09, "amp": [0, 0, 0]}, parent="head"),
        Part("tail", tail_base, [(1, tail(0))], {"speed": 0.06, "amp": [0, 0, 9], "run": {"speed": 0.32, "amp": [10, 0, 24]}}, parent="torso"),
        Part("tail_tip", (-5.7, 6.2, 4.0), [(1, tail(1))], {"speed": 0.06, "phase": -1.0, "amp": [0, 0, 14], "run": {"speed": 0.32, "phase": -1.2, "amp": [12, 0, 30]}}, parent="tail"),
        Part("zzz", (8, -5, 5), [(20, zzz(1)), (20, zzz(2)), (30, zzz(3)), (20, zzz(0))], {"speed": 0.1, "move": [0.3, -0.4, 0]}, when="still"),
    ]
    return model_json(V, O, None, parts)


ANIMATED = [
    ("dragon_wings", "back", "Ender wings", "Torn from the End. They flap when you run.", "free",
     {"m": "D83A145E", "M": "D0662A96", "r": "E0A552E6", "b": "FF1B1226", "h": "FF3C2A55",
      "c": "FFE8DEF4", "s": "FF241832", "S": "FFB46BFF"},
     dragon_wings),
    ("sleeping_cat", "back", "Sleeping cat", "Six cats, and this one picked your back.", "free",
     {"c": "FFFFA94D", "C": "FFFFD7A3", "d": "FFD9772B", "D": "FFA8521A", "p": "FFFF8FB1", "w": "FFFFFFFF",
      "k": "FF2A1A14", "g": "FF7BD86B", "n": "FFFF8FB1", "z": "E0E6EEFF"},
     sleeping_cat),
]


def build():
    items = []
    for (iid, kind, name, desc, avail, palette, fn) in ANIMATED:
        model = fn()
        items.append({"id": iid, "kind": kind, "name": name, "description": desc, "availability": avail,
                      "palette": palette, "model": model})
    return items


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    only = sys.argv[2:] or None
    for it in build():
        if only and it["id"] not in only:
            continue
        path = os.path.join(out, f"{it['id']}.gif")
        gif(it, path)
        print(path)
