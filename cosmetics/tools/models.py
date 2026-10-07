"""Generates the 3D cosmetics (voxel models as ASCII slices) for cosmetics/catalog.json and renders
isometric previews on a reference head/body, so the shapes can be checked before going in-game.

Grid convention (matches the Worker/mod): x = left->right seen from the front, z = 0 front -> back,
y = 0 bottom -> top. layers[0] is the bottom slice; each slice is a list of rows (z), each row a string (x).
"""
import json, math, sys
from PIL import Image, ImageDraw


def to_layers(vox, W, H, D):
    layers = []
    for y in range(H):
        layer = []
        for z in range(D):
            layer.append("".join(vox.get((x, y, z), ".") for x in range(W)))
        layers.append(layer)
    return layers


def disc(cx, cz, r, x, z):
    return (x + 0.5 - cx) ** 2 + (z + 0.5 - cz) ** 2 <= r * r


# ---- hats -------------------------------------------------------------------------------------

def top_hat(gold=False):
    W = D = 12
    H = 9
    v = {}
    body, edge, band, lid, sheen = ("y", "d", "b", "Y", "w") if gold else ("k", "e", "b", "g", "h")
    for x in range(W):
        for z in range(D):
            if disc(6, 6, 6.2, x, z):
                outer = not disc(6, 6, 5.1, x, z)
                v[(x, 0, z)] = edge if outer else body
    for y in range(1, H):
        for x in range(2, 10):
            for z in range(2, 10):
                if not disc(6, 6, 4.3, x, z):
                    continue
                c = body
                if y in (1, 2):
                    c = band
                elif y == H - 1:
                    c = lid
                elif (x, z) in ((3, 2), (2, 3), (3, 3)) or (z == 2 and x == 4):
                    c = sheen  # a vertical highlight on the front-left
                v[(x, y, z)] = c
    # buckle on the band, front centre
    v[(5, 1, 2)] = v[(6, 1, 2)] = v[(5, 2, 2)] = v[(6, 2, 2)] = "B" if not gold else "W"
    return v, W, H, D


def crown():
    W = D = 10
    H = 5
    v = {}
    ring = [(x, z) for x in range(W) for z in range(D) if x in (0, W - 1) or z in (0, D - 1)]
    for (x, z) in ring:
        v[(x, 0, z)] = "d"
        v[(x, 1, z)] = "y"
        v[(x, 2, z)] = "y"
    # gems in the middle of each side (front red, sides blue, back green)
    for (x, z, c) in [(4, 0, "r"), (5, 0, "r"), (0, 4, "b"), (0, 5, "b"), (9, 4, "b"), (9, 5, "b"), (4, 9, "g"), (5, 9, "g")]:
        v[(x, 1, z)] = c
    # spikes: corners and the middle of each side, 2 tall, gold tip + white shine
    spikes = [(0, 0), (9, 0), (0, 9), (9, 9), (4, 0), (5, 0), (0, 4), (0, 5), (9, 4), (9, 5), (4, 9), (5, 9)]
    for (x, z) in spikes:
        v[(x, 3, z)] = "y"
    for (x, z) in [(0, 0), (9, 0), (0, 9), (9, 9)]:
        v[(x, 4, z)] = "Y"
    for (x, z) in [(4, 0), (5, 0), (0, 4), (0, 5), (9, 4), (9, 5), (4, 9), (5, 9)]:
        v[(x, 4, z)] = "w" if (x, z) in ((4, 0), (5, 0)) else "Y"
    v[(4, 4, 0)] = "R"  # a ruby on the front peak
    v[(5, 4, 0)] = "R"
    return v, W, H, D


def cat_ears():
    W, H, D = 10, 4, 3
    v = {}
    for side in (0, 1):
        for y, span in enumerate([4, 3, 2, 1]):
            for i in range(span):
                x = i if side == 0 else W - 1 - i
                for z in range(D):
                    c = "c"
                    if y == 3:
                        c = "d"
                    elif z == 0 and 0 < i < span - 1 + (1 if y == 0 else 0) and y < 2:
                        c = "p"  # pink inside, on the front
                    v[(x, y, z)] = c
    return v, W, H, D


def party_hat():
    W = D = 8
    v = {}
    radii = [3.9, 3.4, 3.0, 2.6, 2.2, 1.8, 1.4, 1.0, 0.71]
    for y, r in enumerate(radii):
        for x in range(W):
            for z in range(D):
                if disc(4, 4, r, x, z):
                    c = ["w", "p", "p", "y", "p", "p", "b", "p", "p"][y]  # pink with clean stripes, white trim
                    v[(x, y, z)] = c
    top = len(radii)
    for (x, z) in [(3, 3), (4, 3), (3, 4), (4, 4)]:
        v[(x, top, z)] = "w"
        v[(x, top + 1, z)] = "W"
    return v, W, top + 2, D


def halo():
    W = D = 10
    v = {}
    for x in range(W):
        for z in range(D):
            if disc(5, 5, 5.0, x, z) and not disc(5, 5, 3.4, x, z):
                inner = not disc(5, 5, 4.2, x, z)
                v[(x, 0, z)] = "y" if inner else "w"
    return v, W, 1, D


def mushroom():
    W = D = 12
    v = {}
    radii = [5.6, 6.1, 6.0, 5.4, 4.4, 2.8]
    spots = {(2, 5), (9, 4), (5, 1), (6, 10), (1, 8), (10, 8), (4, 6), (7, 5)}
    for y, r in enumerate(radii):
        for x in range(W):
            for z in range(D):
                if not disc(6, 6, r, x, z):
                    continue
                if y == 0:
                    v[(x, y, z)] = "c" if disc(6, 6, 4.5, x, z) else "r"
                    continue
                shell = not disc(6, 6, r - 1.2, x, z) or y == len(radii) - 1
                if not shell:
                    v[(x, y, z)] = "r"
                    continue
                spot = any(abs(x - sx) + abs(z - sz) <= 1 for (sx, sz) in spots) and y in (2, 3, 5)
                v[(x, y, z)] = "w" if spot else ("R" if y >= 4 else "r")
    return v, W, len(radii), D


# ---- face --------------------------------------------------------------------------------------

def sunglasses(lens="l", shine="s", frame="k"):
    W, H, D = 10, 2, 9
    v = {}
    top = frame * W
    bottom = frame + lens * 3 + ".." + lens * 3 + frame
    for x, ch in enumerate(bottom):
        if ch != ".":
            v[(x, 0, 0)] = ch
    for x, ch in enumerate(top):
        v[(x, 1, 0)] = ch
    v[(1, 0, 0)] = shine  # a glint on each lens
    v[(6, 0, 0)] = shine
    for z in range(1, D):  # the arms run back along the sides of the head
        v[(0, 1, z)] = frame
        v[(W - 1, 1, z)] = frame
    return v, W, H, D


def glasses_3d():
    W, H, D = 10, 2, 9
    v = {}
    for x in range(W):
        v[(x, 1, 0)] = "w"
    for x, ch in enumerate("wrrr..cccw"):
        if ch != ".":
            v[(x, 0, 0)] = ch
    for z in range(1, D):
        v[(0, 1, z)] = "w"
        v[(W - 1, 1, z)] = "w"
    return v, W, H, D


def mustache():
    W, H, D = 8, 2, 1
    v = {}
    rows = ["..mmmm..", "mmm..mmm"]  # top, bottom
    for y, row in enumerate(reversed(rows)):
        for x, ch in enumerate(row):
            if ch != ".":
                v[(x, y, 0)] = ch
    v[(0, 0, 0)] = "d"
    v[(7, 0, 0)] = "d"
    return v, W, H, D


# ---- back --------------------------------------------------------------------------------------

def backpack():
    W, H, D = 6, 8, 3
    v = {}
    J = ["......", ".ww...", ".w....", ".w....", ".w..w.", "..ww..", "......", "......"]  # top -> bottom; mirrored, it is read from behind
    for y in range(H):
        for x in range(W):
            for z in range(D):
                c = "b"
                if y >= H - 2:
                    c = "l"  # the flap
                elif x in (0, W - 1):
                    c = "d"
                if z == D - 1 and y < H - 2:
                    jrow = J[H - 1 - y]
                    if jrow[x] == "w":
                        c = "w"
                v[(x, y, z)] = c
    # a pocket line under the flap and a zipper pull
    v[(2, H - 3, D - 1)] = v[(3, H - 3, D - 1)] = "d"
    v[(3, H - 2, D - 1)] = "y"
    return v, W, H, D


def wings():
    shape = [  # top -> bottom, seen from behind (symmetric); the gap in the middle is the player's back
        "ww..................ww",
        "www................www",
        "wwwww............wwwww",
        "gwwwwww........wwwwwwg",
        ".gwwwwwww....wwwwwwwg.",
        ".ggwwwwwww..wwwwwwwgg.",
        "..ggwwwwww..wwwwwwgg..",
        "..bggwwwww..wwwwwggb..",
        "...bggwwww..wwwwggb...",
        "....bggwww..wwwggb....",
        ".....bbggw..wggbb.....",
        "......bbgw..wgbb......",
        "........bb..bb........",
    ]
    W, H, D = len(shape[0]), len(shape), 1
    v = {}
    for y, row in enumerate(reversed(shape)):
        assert len(row) == W
        for x, ch in enumerate(row):
            if ch != ".":
                v[(x, y, 0)] = ch
    return v, W, H, D


MODELS = [
    # id, kind, name, description, availability, palette, builder, origin, animation
    ("cat_ears", "hat", "Cat ears", "For whoever has six cats.", "free",
     {"c": "FFFFA94D", "d": "FFD9772B", "p": "FFFF9EC0"},
     cat_ears, [-5, -8.0, -1.5], None),
    ("party_hat", "hat", "Party hat", "Someone joined. Celebrate.", "free",
     {"p": "FFFF5CA8", "y": "FFFFD54A", "b": "FF5BC8FF", "w": "FFFFFFFF", "W": "FFF2F2F2"},
     party_hat, [-4, -8.0, -4], None),
    ("halo", "hat", "Halo", "Floats over the host who never griefs.", "free",
     {"y": "FFFFE066", "w": "FFFFF6C8"},
     halo, [-5, -11, -5], "bob"),
    ("mushroom_cap", "hat", "Mushroom cap", "Grown in the dark oak forest.", "free",
     {"r": "FFE53935", "R": "FFFF5A4F", "w": "FFFFF8EC", "c": "FFF2D7B0"},
     mushroom, [-6, -7.8, -6], None),
    ("founder_hat", "hat", "Founder's hat", "Made jukz. Wears gold.", "grant",
     {"y": "FFFFC24A", "d": "FFE5A82E", "b": "FF5B9BFF", "Y": "FFFFE58A", "w": "FFFFF3C4", "W": "FFFFFFFF"},
     lambda: top_hat(gold=True), [-6, -8.2, -6], None),
    ("sunglasses", "face", "Sunglasses", "Too cool for the spawn chunks.", "free",
     {"k": "FF15151C", "l": "E01C2B4A", "s": "F0AFC8FF"},
     sunglasses, [-5, -3, -4.75], None),
    ("glasses_3d", "face", "3D glasses", "The world looks deeper now.", "free",
     {"w": "FFF4F4F4", "r": "D0FF3B3B", "c": "D03BE0FF"},
     glasses_3d, [-5, -3, -4.75], None),
    ("mustache", "face", "Mustache", "Distinguished.", "free",
     {"m": "FF4A2C17", "d": "FF2E1A0C"},
     mustache, [-4, -0.75, -4.75], None),
    ("jukz_pack", "back", "jukz pack", "Carries the world around.", "free",
     {"b": "FF5B9BFF", "d": "FF2F64C4", "l": "FF8DBBFF", "w": "FFFFFFFF", "y": "FFFFC24A"},
     backpack, [-3, 10, 2], None),
    ("wings", "back", "Wings", "Not a cape. Promise.", "free",
     {"w": "FFFFFFFF", "g": "FFD6DCE8", "b": "FFA9CCFF"},
     wings, [-11, 10, 2.6], None),
]


def build():
    items = []
    for (iid, kind, name, desc, avail, palette, fn, origin, anim) in MODELS:
        vox, W, H, D = fn()
        for ch in set(vox.values()):
            assert ch in palette, (iid, ch)
        model = {"voxel": 1, "origin": origin, "layers": to_layers(vox, W, H, D)}
        if anim:
            model["animation"] = anim
        items.append({"id": iid, "kind": kind, "name": name, "description": desc, "availability": avail,
                      "palette": palette, "model": model, "_vox": (vox, W, H, D)})
    return items


# ---- isometric preview ------------------------------------------------------------------------

def argb(hexs):
    return tuple(int(hexs[i:i + 2], 16) for i in (2, 4, 6)) + (int(hexs[:2], 16),)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


def render(item, S=9, ref=True):
    vox, W, H, D = item["_vox"]
    ox, oy, oz = item["model"]["origin"]
    pal = {k: argb(v) for k, v in item["palette"].items()}
    cubes = {}
    # to bone-space voxel coords: X = ox + x, Y(up) = -(oy) + y, Z = oz + z  (front = small z)
    flip = item["kind"] == "back"  # look at back pieces from behind
    for (x, y, z), ch in vox.items():
        X, Y, Z = ox + x, -oy + y, oz + z
        cubes[(-X - 1, Y, -Z - 1) if flip else (X, Y, Z)] = pal[ch]
    if ref:
        skin = (214, 166, 128, 255)
        if item["kind"] in ("hat", "face"):
            for X in range(-4, 4):
                for Y in range(0, 8):
                    for Z in range(-4, 4):
                        cubes.setdefault((X + 0.0, Y + 0.0, Z + 0.0), skin)
        else:
            shirt = (60, 160, 160, 255)
            for X in range(-4, 4):
                for Y in range(-12, 0):
                    for Z in range(-2, 2):
                        cubes.setdefault((X + 0.0, Y + 0.0, Z + 0.0), shirt)
    # view from the front-left, above: u = -X (towards the player's right = viewer's left side faces us),
    # w = -Z (towards the front). Visible faces: top, the +u face (-X side), the +w face (front).
    img = Image.new("RGBA", (34 * S, 34 * S), (24, 28, 40, 255))
    d = ImageDraw.Draw(img)
    cx, cy = 17 * S, 22 * S

    def P(u, Y, w):
        return (cx + (u - w) * S * 0.87, cy + (u + w) * S * 0.5 - Y * S)

    def key(kv):
        (X, Y, Z), _ = kv
        return (Y, -X - Z)

    for (X, Y, Z), col in sorted(cubes.items(), key=key):
        u0, w0 = -X - 1, -Z - 1
        u1, w1 = u0 + 1, w0 + 1
        top = [P(u0, Y + 1, w0), P(u1, Y + 1, w0), P(u1, Y + 1, w1), P(u0, Y + 1, w1)]
        side = [P(u1, Y, w0), P(u1, Y, w1), P(u1, Y + 1, w1), P(u1, Y + 1, w0)]
        front = [P(u0, Y, w1), P(u1, Y, w1), P(u1, Y + 1, w1), P(u0, Y + 1, w1)]
        d.polygon(side, fill=shade(col, 0.62))
        d.polygon(front, fill=shade(col, 0.82))
        d.polygon(top, fill=shade(col, 1.0))
    return img


if __name__ == "__main__":
    items = build()
    if "--json" in sys.argv:
        print(json.dumps([{k: v for k, v in it.items() if k != "_vox"} for it in items], indent=2))
    else:
        tiles = [render(it) for it in items]
        cols = 3
        w, h = tiles[0].size
        sheet = Image.new("RGBA", (w * cols, h * ((len(tiles) + cols - 1) // cols)), (24, 28, 40, 255))
        for i, t in enumerate(tiles):
            sheet.paste(t, ((i % cols) * w, (i // cols) * h))
            ImageDraw.Draw(sheet).text(((i % cols) * w + 10, (i // cols) * h + 10), items[i]["name"], fill="white")
        sheet.save(sys.argv[1] if len(sys.argv) > 1 else "./models.png")
