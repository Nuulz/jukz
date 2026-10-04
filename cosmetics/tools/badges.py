"""The tab-list badges (16x16 ASCII art) for cosmetics/catalog.json, with a preview sheet.
Shading convention: o = outline, light/base/shadow tones per item, w = specular highlight."""
import json, sys
from PIL import Image, ImageDraw

BADGES = [
    ("jukz", "jukz", "The world cube. Everyone with jukz gets it.", "free",
     {"o": "FF0B1A33", "H": "FFD6E7FF", "T": "FFA9CCFF", "L": "FF5B9BFF", "l": "FF4A87EA", "R": "FF2F64C4",
      "r": "FF2756AD", "W": "FFFFFFFF"},
     [".......oo.......",
      ".....ooHHoo.....",
      "...ooHTTTTHoo...",
      ".ooHTTTTTTTTHoo.",
      "oHTTTTTTTTTTTTHo",
      "oLHHTTTTTTTTHHRo",
      "oLLLHHTTTTHHRRRo",
      "oLLLLLHHHHRRRRRo",
      "oLLlLLLLRRRWWWRo",
      "oLLLLLLLRRRrWrRo",
      "oLLLLLlLRRRrWrRo",
      "oLlLLLLLRWRrWRRo",
      ".ooLLLLLRrWWRoo.",
      "...ooLLLRRRoo...",
      ".....ooLRoo.....",
      ".......oo......."]),
    ("handoff", "Handoff", "Two arrows passing the world around.", "free",
     {"o": "FF0B1A33", "G": "FF8BE38B", "g": "FF4CB04C", "B": "FFA9CCFF", "b": "FF4A87EA",
      "c": "FFFFD54A", "C": "FFE09A00", "w": "FFFFF6C8"},
     None),
    ("heart", "Heart", "A pixel heart, beating for the server.", "free",
     {"o": "FF3A0A1A", "w": "FFFFFFFF", "l": "FFFF9EBB", "r": "FFFF5C8A", "d": "FFD12E63", "D": "FF9E1446"},
     ["................",
      "..oooo....oooo..",
      ".ollrro..orrrro.",
      "olwwlrroorrrrrdo",
      "olwlrrrrrrrrrrdo",
      "olrrrrrrrrrrrddo",
      "orrrrrrrrrrrrddo",
      ".orrrrrrrrrrrdDo",
      ".orrrrrrrrrrddo.",
      "..orrrrrrrrddDo.",
      "...orrrrrrddDo..",
      "....orrrrddDo...",
      ".....orrddDo....",
      "......ordDo.....",
      ".......oDo......",
      "........o......."]),
    ("star", "Star", "Gold star for the best host.", "free",
     {"o": "FF4A2E00", "w": "FFFFFDE8", "Y": "FFFFF08A", "y": "FFFFD54A", "d": "FFE09A00", "D": "FFB87400"},
     [".......oo.......",
      "......oYyo......",
      "......oYyo......",
      ".....oYwyyo.....",
      ".....oYyyyo.....",
      "ooooooYyyydooooo",
      "oYYYYYYyyyyyyddo",
      ".oYYYwYyyyyyddo.",
      "..oYYYYyyyyddo..",
      "...oYYYyyyydo...",
      "...oYYyyyyddo...",
      "..oYYyyddyyddo..",
      "..oYyydoodyyddo.",
      ".oYydoo..oodydo.",
      ".oydo......oddo.",
      ".ooo........ooo."]),
    ("crown", "Crown", "For whoever is hosting right now.", "free",
     {"o": "FF3D2600", "Y": "FFFFF08A", "y": "FFFFD54A", "d": "FFE09A00", "D": "FFB87400", "r": "FFFF5C5C",
      "R": "FFFFC0C0", "b": "FF5B9BFF", "B": "FFCDE2FF", "g": "FF4DD07A", "G": "FFC9F5D7"},
     ["................",
      ".......oo.......",
      ".o....oRro....o.",
      "oBo...orro...oGo",
      "obo....oo....ogo",
      "oyo...oyyo...oyo",
      "oYyo.oyYyyo.oyyo",
      "oYyyoyYYyyyoyydo",
      "oYyyyyyyyyyyyydo",
      "oYyyyyyyyyyyyydo",
      "oddddddddddddDDo",
      "oyRryyBbyyGgyydo",
      "oyrryybbyyggyydo",
      "oddddddddddddDDo",
      ".oooooooooooooo.",
      "................"]),
    ("flame", "Flame", "Keeps the world warm while you're away.", "free",
     {"o": "FF4A0E06", "r": "FFE53935", "R": "FFB71C1C", "O": "FFFF8A1E", "y": "FFFFD54A", "w": "FFFFF6C8"},
     ["........o.......",
      ".......oro......",
      "......orRo...o..",
      "......orrro.oro.",
      ".....orrOrro.oro",
      "..o..orOOrro.oro",
      ".oro.orOOOrrorro",
      ".orroorOOyOrrrro",
      ".orrrrOOyyOOrrro",
      "orrrOOOOyyyOOrro",
      "orrOOOyyywyyOOro",
      "orrOOyyywwwyOOro",
      "orROOyywwwwyOORo",
      ".oRROOyywwyyORo.",
      "..oRRROOOOOORo..",
      "....oooooooo...."]),
    ("moon", "Moon", "Night shift host.", "free",
     {"o": "FF16163A", "m": "FFF2EEDA", "M": "FFFFFFFF", "s": "FFC9C3A5", "S": "FF9E987A", "y": "FFFFE066"},
     [".....oooo.......",
      "...oommMo....y..",
      "..ommMMo....yyy.",
      ".ommmMo......y..",
      ".ommsmo.........",
      "ommmmo......y...",
      "omSmmo.....yyy..",
      "ommmmo......y...",
      "ommmso..........",
      "ommmmmo.......oo",
      ".omsmmmoo...oomo",
      ".ommmSmmmooommso",
      "..ommmmmmsmmmSo.",
      "...oommmmmmmoo..",
      ".....oooooooo...",
      "................"]),
    ("cat", "Cat", "Sits on your world so nobody else does.", "free",
     {"o": "FF1E1E2E", "c": "FFFFA94D", "C": "FFFFC98A", "d": "FFD9772B", "w": "FFFFFFFF", "g": "FF6BCB6B",
      "k": "FF14141C", "p": "FFFF8FB1", "W": "FFE8E8F0"},
     ["................",
      ".oo..........oo.",
      ".odo........odo.",
      ".opdo......odpo.",
      ".oppcoooooocppo.",
      ".occcdcccdcccco.",
      "occcccdcdcccccco",
      "occCCccccccCCcco",
      "occgkccccccgkcco",
      "ocCggcccccggCcco",
      ".owwccccccccwwo.",
      ".owwwccppcccwwo.",
      ".owwwwcoocwwwwo.",
      "..owwwwwwwwwwo..",
      "...oowwwwwwoo...",
      ".....oooooo....."]),
    ("gem", "Gem", "Found it at diamond level.", "free",
     {"o": "FF062A33", "w": "FFFFFFFF", "L": "FFB8F6FF", "c": "FF5FE3F5", "C": "FF2BB8D6", "D": "FF16809C"},
     ["................",
      "................",
      "....oooooooo....",
      "...owLLcccCCo...",
      "..owLLccccCCCo..",
      ".owLLccccccCCDo.",
      "oLLLLLLLccccCCDo",
      ".oLLcccccccCCDo.",
      "..oLccccccCCDo..",
      "...oLccccCCDo...",
      "....oLcccCDo....",
      ".....oLcCDo.....",
      "......oLDo......",
      ".......oo.......",
      "................",
      "................"]),
    ("planet", "Planet", "A whole world, ringed and orbiting.", "free",
     {"o": "FF141A3A", "b": "FF7FB2FF", "B": "FF4A87EA", "D": "FF2F64C4", "g": "FF6BCB6B", "G": "FF3C9A50",
      "r": "FFFFD9A0", "R": "FFE0A060", "w": "FFFFFFFF"},
     ["................",
      ".....oooooo.....",
      "...oobbbggbBoo..",
      "..obwbbgggbBBo..",
      ".obwbbbbgbBBBBo.",
      ".obbggbbbbBBDBo.",
      "rrrrrrrrrrrrrrrR",
      "RRobgggbbBBDDoRR",
      "..RRRRRRRRRRRRR.",
      ".obbbbbbBBBDDDo.",
      "..obbbbBBBDDDo..",
      "...ooBBBDDDoo...",
      ".....oooooo.....",
      "................",
      "................",
      "................"]),
    ("mushroom", "Mushroom", "Red cap, white spots, good vibes.", "free",
     {"o": "FF3A0C0C", "r": "FFE53935", "R": "FFFF6F5E", "d": "FFB71C1C", "w": "FFFFF8EC", "s": "FFF2D7B0",
      "S": "FFD9B88A"},
     ["................",
      ".....oooooo.....",
      "...ooRRrrrroo...",
      "..oRRwwrrrwwdo..",
      ".oRRwwwrrrwwddo.",
      ".oRrwwrrrrrrddo.",
      "oRrrrrrrwwrrrddo",
      "orwwrrrwwwwrrwdo",
      "orwwrrrrwwrrwwdo",
      ".odddddddddddddo",
      "..ooooossssooooo",
      "......osssSo....",
      "......ossSSo....",
      "......osssSo....",
      ".....oossSSoo...",
      "......oooooo...."]),
    ("founder", "Founder", "The person who made jukz.", "grant",
     {"o": "FF2A1A00", "H": "FFFFF6C8", "T": "FFFFE58A", "L": "FFFFC24A", "l": "FFF0B030", "R": "FFD99100",
      "r": "FFB87400", "b": "FF5B9BFF", "w": "FFFFFFFF"},
     [".......oo.......",
      ".....ooHHoo.....",
      "...ooHTwwTHoo...",
      ".ooHTTTwwTTTHoo.",
      "oHTTwwwwwwwwTTHo",
      "oLHHTTTwwTTTHHRo",
      "oLLLHHTwwTHHRRRo",
      "oLLLLLHHHHRRRRRo",
      "oLLlLLLLRRRbbbRo",
      "oLLLLLLLRRRrbrRo",
      "oLLLLLlLRRRrbrRo",
      "oLlLLLLLRbRrbRRo",
      ".ooLLLLLRrbbRoo.",
      "...ooLLLRRRoo...",
      ".....ooLRoo.....",
      ".......oo......."]),
]


def outlined(px):
    """Fill every empty pixel touching a filled one with the outline colour 'o'."""
    grid = [list(r) for r in px]
    for y in range(16):
        for x in range(16):
            if px[y][x] != ".":
                continue
            if any(0 <= y + dy < 16 and 0 <= x + dx < 16 and px[y + dy][x + dx] != "."
                   for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                grid[y][x] = "o"
    return ["".join(r) for r in grid]


def handoff():
    px = [["."] * 16 for _ in range(16)]
    cx = cy = 7.5
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - 8) ** 2 + (y + 0.5 - 8) ** 2) ** 0.5
            if 4.3 <= d <= 6.2:
                if 6 <= y <= 8 and (x >= 12 or x <= 3):
                    continue  # the gaps the arrows point through
                top = y < 8
                inner = d < 5.2
                px[y][x] = ("g" if inner else "G") if top else ("b" if inner else "B")
    # green arrowhead at the right, pointing down; blue at the left, pointing up
    for i, (a, b) in enumerate([(10, 15), (11, 14), (12, 13)]):
        for x in range(a, b + 1):
            px[6 + i][x] = "G" if x < (a + b) / 2 + 0.5 else "g"
    for i, (a, b) in enumerate([(0, 5), (1, 4), (2, 3)]):
        for x in range(a, b + 1):
            px[9 - i][x] = "B" if x < (a + b) / 2 + 0.5 else "b"
    # the world in the middle: a tiny gold cube
    for y, row in enumerate([".cc.", "cwcC", "ccCC", ".CC."]):
        for x, ch in enumerate(row):
            if ch != ".":
                px[6 + y][6 + x] = ch
    return outlined(["".join(r) for r in px])


def build():
    out = []
    for (iid, name, desc, avail, pal, art) in BADGES:
        if art is None:
            art = {"handoff": handoff}[iid]()
        assert len(art) == 16 and all(len(r) == 16 for r in art), (iid, [len(r) for r in art])
        for r in art:
            for ch in r:
                assert ch == "." or ch in pal, (iid, ch)
        out.append({"id": iid, "kind": "badge", "name": name, "description": desc, "availability": avail,
                    "palette": pal, "art": art})
    return out


def preview(items, path, S=12):
    pad = 16
    img = Image.new("RGBA", (len(items) * (16 * S + pad) + pad, 16 * S + 2 * pad + 40), (20, 24, 36, 255))
    for k, it in enumerate(items):
        x0 = pad + k * (16 * S + pad)
        for y, row in enumerate(it["art"]):
            for x, ch in enumerate(row):
                if ch == ".":
                    continue
                c = it["palette"][ch]
                rgb = tuple(int(c[i:i + 2], 16) for i in (2, 4, 6))
                for dy in range(S):
                    for dx in range(S):
                        img.putpixel((x0 + x * S + dx, pad + y * S + dy), rgb + (255,))
                for dy in range(2):
                    for dx in range(2):
                        img.putpixel((x0 + x * 2 + dx, 16 * S + pad + 8 + y * 2 + dy), rgb + (255,))
    img.save(path)


if __name__ == "__main__":
    items = build()
    if "--json" in sys.argv:
        print(json.dumps(items, indent=2))
    else:
        preview(items[:6], "./badges1.png")
        preview(items[6:], "./badges2.png")
