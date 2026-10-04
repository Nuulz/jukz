"""Community cosmetics from the creators page (nuulm.com/jukz/crear): review queue → jukz catalog.

    python3 creations.py list [pending|approved|published|rejected|all]
    python3 creations.py pull <submission-id> [--item ID]   # download, voxelize, write ../creations/<ID>.json + preview
    python3 creations.py publish <submission-id> <item-id>  # after deploying the catalog: mark it published
                                                            # (grants it, and the creator's extra pick, to them)
Options: --local (the wrangler dev Worker on :18791). The admin token comes from $JUKZ_ADMIN_TOKEN or
~/.config/jukz/cosmetics-admin-token.

Typical flow: approve on the page (#admin) → `pull` → look at the preview, tweak the JSON's origin if it
sits wrong → `python3 build_catalog.py` → deploy the Worker (npx wrangler deploy) → `publish`.

Voxelizing: every Blockbench cube (rotations included) is sampled on the 1-pixel grid; each voxel takes the
colour of the nearest textured face at that spot, transparent texels become empty, and colours are reduced
to a palette of at most 52 (one character each).
"""
import base64
import io
import json
import math
import os
import re
import sys
import urllib.request

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "creations")
CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"


def api_base(local):
    return "http://127.0.0.1:18791/v1/creators" if local else "https://jukz.nuulm.com/v1/creators"


def admin_token(local):
    if local:
        return "dev-admin"
    token = os.environ.get("JUKZ_ADMIN_TOKEN")
    if token:
        return token.strip()
    with open(os.path.expanduser("~/.config/jukz/cosmetics-admin-token")) as f:
        return f.read().strip()


def call(local, path, body=None, raw=False):
    req = urllib.request.Request(api_base(local) + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"x-jukz-admin": admin_token(local), "content-type": "application/json",
                                          "user-agent": "jukz-creations/1"})
    try:
        with urllib.request.urlopen(req) as res:
            data = res.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{e.code}: {e.read().decode(errors='replace')}")
    return data if raw else json.loads(data)


# ---- voxelizing ----------------------------------------------------------------------------------

def rot_matrix(deg):
    """Blockbench rotates cubes around their origin in Z, then Y, then X (three.js order 'ZYX')."""
    rx, ry, rz = (math.radians(d) for d in deg)
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    X = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    Y = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    Z = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    mul = lambda a, b: [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(mul(Z, Y), X)  # euler 'ZYX' in three.js = Rz · Ry · Rx applied to column vectors


def apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def transpose(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def load_textures(bb):
    res_w = (bb.get("resolution") or {}).get("width", 16)
    res_h = (bb.get("resolution") or {}).get("height", 16)
    out = []
    for t in bb.get("textures") or []:
        src = t.get("source") or ""
        if not src.startswith("data:image"):
            out.append(None)
            continue
        img = Image.open(io.BytesIO(base64.b64decode(src.split(",", 1)[1]))).convert("RGBA")
        out.append((img, t.get("uv_width") or res_w, t.get("uv_height") or res_h))
    return out


def face_uv(name, p, fr, to):
    """Where point p (cube-local) falls on face `name`, as (s, t) in 0..1 across the face's uv box."""
    sx, sy, sz = (max(to[i] - fr[i], 1e-6) for i in range(3))
    x, y, z = ((p[i] - fr[i]) for i in range(3))
    return {
        "north": ((sx - x) / sx, (sy - y) / sy),
        "south": (x / sx, (sy - y) / sy),
        "east": ((sz - z) / sz, (sy - y) / sy),
        "west": (z / sz, (sy - y) / sy),
        "up": (x / sx, z / sz),
        "down": (x / sx, (sz - z) / sz),
    }[name]


def sample(textures, face, s, t):
    tex = textures[face["texture"]] if isinstance(face.get("texture"), int) and face["texture"] < len(textures) else None
    if tex is None:
        return (138, 173, 244, 255)  # untextured faces: jukz blue
    img, uw, uh = tex
    u1, v1, u2, v2 = face.get("uv") or [0, 0, uw, uh]
    u = u1 + (u2 - u1) * min(max(s, 0), 0.9999)
    v = v1 + (v2 - v1) * min(max(t, 0), 0.9999)
    px = min(int(u / uw * img.width), img.width - 1)
    py = min(int(v / uh * img.height), img.height - 1)
    return img.getpixel((px, py))


def voxelize(bb):
    textures = load_textures(bb)
    cubes = []
    lo, hi = [math.inf] * 3, [-math.inf] * 3
    for el in bb.get("elements") or []:
        if el.get("type", "cube") != "cube" or el.get("visibility") is False:
            continue
        fr, to = el["from"], el["to"]
        origin = el.get("origin") or [0, 0, 0]
        m = rot_matrix(el.get("rotation") or [0, 0, 0])
        corners = [[(to if (i >> k) & 1 else fr)[k] for k in range(3)] for i in range(8)]
        for c in corners:
            w = [a + b for a, b in zip(apply(m, [c[k] - origin[k] for k in range(3)]), origin)]
            lo = [min(lo[k], w[k]) for k in range(3)]
            hi = [max(hi[k], w[k]) for k in range(3)]
        faces = {n: f for n, f in (el.get("faces") or {}).items() if f is not None and f.get("texture") is not None}
        cubes.append((fr, to, origin, transpose(m), faces))
    if not cubes:
        sys.exit("no cubes in the model")
    gl = [math.floor(v + 1e-6) for v in lo]
    gh = [math.ceil(v - 1e-6) for v in hi]
    W, H, D = gh[0] - gl[0], gh[1] - gl[1], gh[2] - gl[2]
    vox = {}
    for xi in range(W):
        for yi in range(H):
            for zi in range(D):
                c = [gl[0] + xi + 0.5, gl[1] + yi + 0.5, gl[2] + zi + 0.5]
                for fr, to, origin, inv, faces in reversed(cubes):  # later cubes win
                    p = [a + b for a, b in zip(apply(inv, [c[k] - origin[k] for k in range(3)]), origin)]
                    if not all(fr[k] - 1e-3 <= p[k] <= to[k] + 1e-3 for k in range(3)):
                        continue
                    if not faces:
                        break
                    dist = {"west": p[0] - fr[0], "east": to[0] - p[0], "down": p[1] - fr[1], "up": to[1] - p[1],
                            "north": p[2] - fr[2], "south": to[2] - p[2]}
                    name = min(faces, key=lambda n: dist[n])
                    rgba = sample(textures, faces[name], *face_uv(name, p, fr, to))
                    if rgba[3] >= 128:
                        # grid: x left→right as Blockbench's +x, z from the front (north, -z), y from the bottom
                        vox[(xi, yi, zi)] = rgba[:3]
                    break
    return vox, W, H, D


def palette_of(vox):
    colours = sorted(set(vox.values()))
    if len(colours) > len(CHARS):
        strip = Image.new("RGB", (len(colours), 1))
        strip.putdata(colours)
        q = strip.quantize(colors=len(CHARS), method=Image.Quantize.MEDIANCUT).convert("RGB")
        remap = dict(zip(colours, q.getdata()))
        vox = {k: remap[v] for k, v in vox.items()}
        colours = sorted(set(vox.values()))
    chars = {c: CHARS[i] for i, c in enumerate(colours)}
    return vox, chars


ORIGINS = {
    # where the grid's left-front-bottom corner goes, in the bone's pixel space (see the catalog README)
    "hat": lambda W, H, D: [-W / 2, -8.1, -D / 2],          # sitting on the head
    "face": lambda W, H, D: [-W / 2, -4 + H / 2, -4.6],       # in front of the face, centred on the eyes
    "back": lambda W, H, D: [-W / 2, 6 + H / 2, 2.1],        # against the back, centred on the body
}


def to_item(bb, sub, item_id):
    vox, W, H, D = voxelize(bb)
    vox, chars = palette_of(vox)
    layers = [["".join(chars[vox[(x, y, z)]] if (x, y, z) in vox else "." for x in range(W)) for z in range(D)] for y in range(H)]
    origin = [round(v, 2) for v in ORIGINS[sub["slot"]](W, H, D)]
    item = {
        "id": item_id, "kind": sub["slot"], "name": sub["title"][:40],
        "description": f"Made by {sub['creator']}." if not sub.get("notes") else sub["notes"][:120],
        "availability": "free", "author": sub["creator"],
        "palette": {ch: "FF%02X%02X%02X" % c for c, ch in chars.items()},
        "model": {"voxel": 1, "origin": origin, "layers": layers},
    }
    return item, (vox_for_preview(vox, chars), W, H, D)


def vox_for_preview(vox, chars):
    return {k: chars[v] for k, v in vox.items()}


# ---- commands -------------------------------------------------------------------------------------

def cmd_list(local, status):
    q = "" if status == "all" else f"?status={status}"
    for s in call(local, "/admin/submissions" + q)["submissions"]:
        print(f"{s['id']}  {s['status']:<9} {s['slot']:<4} {s['creator']:<16} {'✓' if s['verified'] else ' '} {s['title']}"
              f"  ({s['summary']['elements']} cubes, {'×'.join(str(n) for n in s['summary']['size'])})"
              + (f"  → {s['itemId']}" if s.get("itemId") else ""))


def cmd_pull(local, sid, item_id):
    subs = call(local, "/admin/submissions?")["submissions"]
    sub = next((s for s in subs if s["id"] == sid), None) or sys.exit(f"no submission {sid}")
    bb = json.loads(call(local, f"/submissions/{sid}/model", raw=True))
    item_id = item_id or re.sub(r"[^a-z0-9_]+", "_", f"{sub['creator']}_{sub['title']}".lower()).strip("_")[:32]
    item, preview = to_item(bb, sub, item_id)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{item_id}.json")
    with open(path, "w") as f:
        json.dump(item, f, indent=2)
        f.write("\n")
    print(f"wrote {os.path.relpath(path)}  ({len(item['palette'])} colours, {preview[1]}×{preview[2]}×{preview[3]})")
    try:
        sys.path.insert(0, HERE)
        import models
        img = models.render({**item, "_vox": preview})
        png = os.path.join(HERE, f"preview-{item_id}.png")
        img.save(png)
        print(f"preview {os.path.relpath(png)}")
    except Exception as e:  # the preview is a convenience
        print(f"(no preview: {e})")
    print("next: python3 build_catalog.py → deploy the Worker →", f"python3 creations.py publish {sid} {item_id}")


def cmd_publish(local, sid, item_id):
    row = call(local, f"/admin/submissions/{sid}", {"status": "published", "itemId": item_id, "note": "It's in jukz!"})
    print(f"{row['id']} → {row['status']} as {row['itemId']} (creator {row['creator']}{', verified' if row['verified'] else ', not verified yet'})")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--local"]
    local = "--local" in sys.argv
    item = None
    if "--item" in args:
        i = args.index("--item")
        item = args[i + 1]
        del args[i:i + 2]
    if not args or args[0] not in ("list", "pull", "publish"):
        sys.exit(__doc__)
    if args[0] == "list":
        cmd_list(local, args[1] if len(args) > 1 else "pending")
    elif args[0] == "pull":
        cmd_pull(local, args[1], item)
    else:
        cmd_publish(local, args[1], args[2])
