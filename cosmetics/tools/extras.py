"""Trails and emotes for cosmetics/catalog.json.

    python3 extras.py [out.png]     # preview sheet: icons, particle sprites, emotes
"""
import sys

SPARKS_ICON = [
    "................",
    ".......a........",
    ".......a........",
    "......aba.......",
    "..a..abcba..a...",
    ".....abcba......",
    "......aba.......",
    ".......a....b...",
    ".......a...bcb..",
    "...b........b...",
    "..bcb...........",
    "...b.....a......",
    ".........a......",
    "......aabcbaa...",
    ".........a......",
    ".........a......",
]
SPARKS_SPRITES = [
    [".....", "..a..", ".aca.", "..a..", "....."],
    ["..a..", "..b..", "abcba", "..b..", "..a.."],
    ["a...a", ".bcb.", ".ccc.", ".bcb.", "a...a"],
]

PETALS_ICON = [
    "................",
    "..........ss....",
    ".........sbbs...",
    "...ss....sbabs..",
    "..sbbs....sbbs..",
    ".sbaabs....ss...",
    ".sbaaabs........",
    "..sbaabs........",
    "...sbbs.....s...",
    "....ss.....sbs..",
    "..........sbabs.",
    "......ss...sbbs.",
    ".....sbbs...ss..",
    "....sbaabs......",
    ".....sbbs.......",
    "......ss........",
]
PETALS_SPRITES = [
    [".....", "..sb.", ".sbab", ".sbbs", "..s.."],
    ["..s..", ".sbs.", "sbabs", ".sbs.", "..s.."],
    [".....", ".ss..", "sbas.", ".sbbs", "..ss."],
]

BITS_ICON = [
    "................",
    "....tt..........",
    "...tttt.........",
    "...lttr.........",
    "...llrr.....tt..",
    "....lr.....tttt.",
    "...........lttr.",
    "........tt.llrr.",
    ".......tttt.lr..",
    ".......lttr.....",
    "..tt...llrr.....",
    ".tttt...lr......",
    ".lttr...........",
    ".llrr...........",
    "..lr............",
    "................",
]
BITS_SPRITES = [
    ["ttt", "tlt", "ttt"],
    ["lll", "ltl", "lll"],
    ["rrr", "rtr", "rrr"],
]

TRAILS = [
    ("trail_sparks", "Sparks", "Little stars that twinkle out where you walk.", "twinkle", 0.035,
     {"a": "FFFFD54A", "b": "FFFFE58A", "c": "FFFFFFFF"}, SPARKS_ICON, SPARKS_SPRITES),
    ("trail_petals", "Petals", "Cherry petals drifting down behind you.", "fall", 0.03,
     {"s": "FFD9468A", "b": "FFFF8FB1", "a": "FFFFE3EE"}, PETALS_ICON, PETALS_SPRITES),
    ("trail_bits", "jukz bits", "Little cubes of world that bounce off your heels.", "bounce", 0.035,
     {"t": "FF8DBBFF", "l": "FF5B9BFF", "r": "FF2F64C4"}, BITS_ICON, BITS_SPRITES),
]

FONT = {
    "g": ["0111", "1000", "1000", "1011", "1001", "0111"],
    "l": ["1000", "1000", "1000", "1000", "1000", "1111"],
    "a": ["0110", "1001", "1001", "1111", "1001", "1001"],
}

N = 20


def bubble(text=None, inner=None):
    """A 20x20 speech bubble: outline o, shadow s, fill w, shine h; content in the middle."""
    g = [["."] * N for _ in range(N)]
    x0, x1, y0, y1 = 1, 18, 1, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if x in (x0, x1) and y in (y0, y1):
                continue
            inner_corner = x in (x0 + 1, x1 - 1) and y in (y0 + 1, y1 - 1)
            g[y][x] = "o" if x in (x0, x1) or y in (y0, y1) or inner_corner else "w"
    for x in range(x0 + 2, x1 - 1):
        g[y1 - 1][x] = "s"
    for x in range(x0 + 3, x0 + 6):
        g[y0 + 1][x] = "h"
    g[y0 + 2][x0 + 2] = "h"
    for (x, y, c) in [(4, 13, "w"), (5, 13, "w"), (3, 14, "o"), (4, 14, "w"), (5, 14, "o"), (3, 15, "o"), (4, 15, "o"), (2, 16, "o")]:
        g[y][x] = c
    if text:
        width = sum(len(FONT[ch][0]) for ch in text) + len(text) - 1
        cx = (N - width) // 2
        for ch in text:
            for dy, line in enumerate(FONT[ch]):
                for dx, bit in enumerate(line):
                    if bit == "1":
                        g[4 + dy][cx + dx] = "t"
            for dy, line in enumerate(FONT[ch]):
                for dx, bit in enumerate(line):
                    if bit == "1" and g[5 + dy][cx + dx + 1] == "w":
                        g[5 + dy][cx + dx + 1] = "T"
            cx += len(FONT[ch][0]) + 1
    if inner:
        ix = (N - len(inner[0])) // 2
        for dy, line in enumerate(inner):
            for dx, ch in enumerate(line):
                if ch != ".":
                    g[3 + dy][ix + dx] = ch
    return ["".join(r) for r in g]


HEART = [
    "..rr...rr..",
    ".rRRr.rRRr.",
    "rRWRRrRRRRr",
    "rRRRRRRRRRr",
    ".rRRRRRRRr.",
    "..rRRRRRr..",
    "...rRRRr...",
    "....rRr....",
    ".....r.....",
]
SMILE = [
    "...........",
    "..kk...kk..",
    "..kk...kk..",
    "...........",
    ".p.......p.",
    ".k.......k.",
    "..k.....k..",
    "...kkkkk...",
    "...........",
]

def coin_frames():
    """The angel's coin toss: a gold coin flips up spinning (halo on one face, wings on the other),
    lands on the halo side and shines. 32x32 frames, played one after the other over the 3 s."""
    import math
    N = 32
    WING = ["w.....w", "ww...ww", "www.www", ".wwwww."]

    def frame(cy, spin, glow=0):
        g = [["."] * N for _ in range(N)]
        R = 7.5
        w = abs(math.cos(spin)) * R
        heads = math.cos(spin) >= 0
        for y in range(N):
            for x in range(N):
                dx, dy = x + 0.5 - 16, y + 0.5 - cy
                if w < 1.2:
                    if abs(dx) < 1 and abs(dy) < R:
                        g[y][x] = "d"
                    continue
                e = (dx / w) ** 2 + (dy / R) ** 2
                if e > 1:
                    continue
                if e > 0.68:
                    g[y][x] = "d"
                    continue
                g[y][x] = "Y" if dx / w + dy / R < -0.9 else "y"
                if w < 3.5:
                    continue
                if heads:
                    # the halo: a ring floating over the face
                    u = dx / w * R
                    h = (u / 3.2) ** 2 + ((dy + 2.5) / 1.3) ** 2
                    if 0.3 < h < 1.7 and not (abs(u) < 2.2 and abs(dy + 2.5) < 0.6):
                        g[y][x] = "w"
                    elif u * u + (dy - 1.5) ** 2 < 3.2:
                        g[y][x] = "o"
        if not heads and w >= 3.5:
            for j, row in enumerate(WING):
                for i, ch in enumerate(row):
                    xx = 16 + round((i - 3) * w / R)
                    if ch == "w" and 0 <= xx < N:
                        g[int(cy) - 2 + j][xx] = "w"
        # sparkles once it lands
        for k in range(glow):
            a = k * 2 * math.pi / 6 + glow * 0.4
            px, py = int(16 + 10 * math.cos(a)), int(cy + 9 * math.sin(a))
            for ox, oy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                if 0 <= px + ox < N and 0 <= py + oy < N and g[py + oy][px + ox] == ".":
                    g[py + oy][px + ox] = "s" if (ox, oy) == (0, 0) else "S"
        return ["".join(r) for r in g]

    frames = [frame(23.5, 0)] * 3
    T = 14
    for k in range(T):
        t = (k + 1) / T
        frames.append(frame(23.5 - 56 * t * (1 - t), t * 5 * math.pi))
    frames += [frame(23.5, 0.4), frame(23.5, -0.2), frame(23.5, 0)]
    frames += [frame(23.5, 0, glow=n) for n in (2, 4, 6, 6)]
    return frames


COIN = coin_frames()
# the coin held in the hand: the halo face, tight around it
COIN_PROP = [r[8:24] for r in COIN[0][15:31]]


EMOTES = [
    ("emote_gg", "gg", "Good game.", bubble("gg"), {"t": "FF2F64C4", "T": "FF9DBDF5"}),
    ("emote_lag", "lag", "It's not you, it's the wifi.", bubble("lag"), {"t": "FFE0213F", "T": "FFF5A3AF"}),
    ("emote_heart", "Heart", "Says it without words.", bubble(inner=HEART), {"r": "FFB8204F", "R": "FFFF5C8A", "W": "FFFFD6E2"}),
    ("emote_smile", "Smile", ":)", bubble(inner=SMILE), {"k": "FF1A1A22", "p": "FFFF9EB5"}),
]
BUBBLE_PALETTE = {"o": "FF0B1A33", "w": "FFFFFFFF", "s": "FFD3DEEF", "h": "FFE9F1FF"}


def check(iid, art, pal):
    assert all(len(r) == len(art) for r in art), (iid, [len(r) for r in art])
    assert all(ch == "." or ch in pal for r in art for ch in r), iid


def build():
    items = []
    for (iid, name, desc, style, size, pal, icon, sprites) in TRAILS:
        for a in [icon] + sprites:
            check(iid, a, pal)
        items.append({"id": iid, "kind": "trail", "name": name, "description": desc, "availability": "free",
                      "palette": pal, "art": icon, "particle": {"style": style, "size": size, "sprites": sprites}})
    for (iid, name, desc, art, pal) in EMOTES:
        pal = {**BUBBLE_PALETTE, **pal}
        check(iid, art, pal)
        items.append({"id": iid, "kind": "emote", "name": name, "description": desc, "availability": "free",
                      "palette": pal, "art": art})
    pal = {"y": "FFFFD54A", "Y": "FFFFF0A0", "d": "FFC98A12", "w": "FFFFFFFF", "o": "FFFFF6C8", "s": "FFFFFFFF", "S": "B0FFF0A0"}
    check("emote_coin", COIN[-1], pal)
    check("emote_coin", COIN_PROP, pal)
    # a gesture: the wearer flicks the coin up with the thumb and catches it (no bubble)
    items.append({"id": "emote_coin", "kind": "emote", "name": "Angel's coin",
                  "description": "Only for those who wear the halo and the wings. Flip it, heads always.",
                  "availability": "free", "palette": pal, "art": COIN[-1], "gesture": "coin_flip", "prop": COIN_PROP,
                  "requires": ["halo", "wings"]})
    return items


if __name__ == "__main__":
    from PIL import Image
    S = 10
    arts = []
    for it in build():
        arts.append((it["art"], it["palette"]))
        for sp in it.get("particle", {}).get("sprites", []):
            arts.append((sp, it["palette"]))
    img = Image.new("RGBA", (len(arts) * (N * S + 10), N * S + 10), (30, 34, 48, 255))
    for n, (art, pal) in enumerate(arts):
        for y, row in enumerate(art):
            for x, ch in enumerate(row):
                if ch != ".":
                    h = pal[ch]
                    col = tuple(int(h[k:k + 2], 16) for k in (2, 4, 6)) + (255,)
                    for a in range(S):
                        for b in range(S):
                            img.putpixel((n * (N * S + 10) + 5 + x * S + a, 5 + y * S + b), col)
    img.save(sys.argv[1] if len(sys.argv) > 1 else "extras.png")
