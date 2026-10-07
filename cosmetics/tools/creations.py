"""Community cosmetics from the creators page (nuulm.com/jukz/crear): review queue → jukz catalog.

    python3 creations.py list [pending|approved|published|rejected|all]
    python3 creations.py pull <submission-id> [--item ID]   # download, voxelize, write ../creations/<ID>.json + preview
    python3 creations.py publish <submission-id> <item-id>  # after deploying the catalog: mark it published
                                                            # (grants it, and the creator's extra pick, to them)
    python3 creations.py try <file.bbmodel> <hat|face|back> # convert a local file: preview only, nothing is written
                                                            # to the catalog (the guide's template, a creator's file…)
Options: --local (the wrangler dev Worker on :18791). The admin token comes from $JUKZ_ADMIN_TOKEN or
~/.config/jukz/cosmetics-admin-token.

Typical flow: approve on the page (#admin) → `pull` → look at the preview, tweak the JSON's origin if it
sits wrong → `python3 build_catalog.py` → deploy the Worker (npx wrangler deploy) → `publish`.

Voxelizing: every Blockbench cube (rotations included) is sampled on the 1-pixel grid (half-pixel when the
model uses half-pixel cubes); each voxel takes the colour of the nearest textured face at that spot,
transparent texels become empty, and colours are reduced to a palette of at most 52 (one character each).

Animated models (the guide at nuulm.com/jukz/guia): each animated Blockbench group becomes a rig part that
turns around the group's pivot, nested groups move with their parent, and the animations named idle, walk
and sneak become its motion, fitted to one smooth back-and-forth wave per loop. Group names can carry
frames (`eyes#1=170`, `eyes#2=6`: shown in turn, for that many ticks) and a state (`zzz@still`, also
@moving, @sneaking, @standing). A group named "reference" (the template's player) is left out, and when it
is there the model keeps its exact place on the player instead of being centred.
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


def cube_list(bb):
    """Every visible cube as (uuid, from, to, origin, inverse rotation, faces, world corners)."""
    textures = load_textures(bb)
    cubes = []
    for el in bb.get("elements") or []:
        if el.get("type", "cube") != "cube" or el.get("visibility") is False:
            continue
        fr, to = el["from"], el["to"]
        origin = el.get("origin") or [0, 0, 0]
        m = rot_matrix(el.get("rotation") or [0, 0, 0])
        corners = [[(to if (i >> k) & 1 else fr)[k] for k in range(3)] for i in range(8)]
        world = [[a + b for a, b in zip(apply(m, [c[k] - origin[k] for k in range(3)]), origin)] for c in corners]
        faces = {n: f for n, f in (el.get("faces") or {}).items() if f is not None and f.get("texture") is not None}
        cubes.append((el.get("uuid"), fr, to, origin, transpose(m), faces, world))
    return cubes, textures


def voxel_size(cubes):
    """Half a pixel when the model is built from half-pixel cubes, else one."""
    fine = any(abs(v * 2 - round(v * 2)) < 1e-3 and abs(v - round(v)) > 1e-3 for c in cubes for v in c[1] + c[2])
    return 0.5 if fine else 1


def bounds(cubes, v):
    lo = [min(w[k] for c in cubes for w in c[6]) for k in range(3)]
    hi = [max(w[k] for c in cubes for w in c[6]) for k in range(3)]
    gl = [math.floor(x / v + 1e-6) * v for x in lo]
    gh = [math.ceil(x / v - 1e-6) * v for x in hi]
    return gl, [round((gh[k] - gl[k]) / v) for k in range(3)]


def sample_cells(cubes, textures, gl, dims, v):
    """Voxels of these cubes on the grid starting at gl (Blockbench units), keyed (x, y up, z from north)."""
    W, H, D = dims
    vox = {}
    for xi in range(W):
        for yi in range(H):
            for zi in range(D):
                c = [gl[0] + (xi + 0.5) * v, gl[1] + (yi + 0.5) * v, gl[2] + (zi + 0.5) * v]
                for _, fr, to, origin, inv, faces, _w in reversed(cubes):  # later cubes win
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
    return vox


def voxelize(bb):
    cubes, textures = cube_list(bb)
    if not cubes:
        sys.exit("no cubes in the model")
    gl, (W, H, D) = bounds(cubes, 1)
    return sample_cells(cubes, textures, gl, (W, H, D), 1), W, H, D


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
    if is_rigged(bb):
        return rig_item(bb, sub, item_id)
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


# ---- animated models (rig) ------------------------------------------------------------------------

REFERENCE = {"reference", "referencia", "jukz_reference"}
NAME_RE = re.compile(r"^(?P<name>[A-Za-z0-9_]+?)(?:#(?P<frame>\d+)(?:=(?P<ticks>\d+))?)?(?:@(?P<when>still|moving|sneaking|standing))?$")
STATES = {"idle": None, "walk": "run", "run": "run", "sneak": "sneak"}


def outline(bb):
    """The group tree as dicts {name, origin, uuid, cubes: [uuid], children: [...]}, Blockbench 4 and 5 files."""
    by_uuid = {g["uuid"]: g for g in bb.get("groups") or [] if isinstance(g, dict) and "uuid" in g}

    def node(n):
        if isinstance(n, str):
            return None
        g = by_uuid.get(n.get("uuid"), {}) if "name" not in n else n
        g = {**g, **{k: v for k, v in n.items() if k in ("children",)}}
        out = {"name": g.get("name", "group"), "origin": g.get("origin") or [0, 0, 0], "uuid": g.get("uuid") or n.get("uuid"),
               "visible": g.get("visibility", True), "cubes": [], "children": []}
        for ch in n.get("children") or []:
            if isinstance(ch, str):
                out["cubes"].append(ch)
            else:
                child = node(ch)
                if child:
                    out["children"].append(child)
        return out

    root = {"name": None, "origin": [0, 0, 0], "uuid": None, "cubes": [], "children": []}
    for n in bb.get("outliner") or []:
        if isinstance(n, str):
            root["cubes"].append(n)
        else:
            root["children"].append(node(n))
    return root


def numeric(v):
    try:
        return float(str(v).replace(",", "."))
    except ValueError:
        return None


def channel_curve(keyframes, length, steps=64):
    """Samples a Blockbench channel (linear between keyframes, looping) at `steps` points over the loop: [(x, y, z)]."""
    pts = []
    for kf in keyframes:
        dp = (kf.get("data_points") or [{}])[0]
        vals = [numeric(dp.get(a, 0)) for a in "xyz"]
        if None in vals:
            print(f"  ! a {kf.get('channel')} keyframe uses a formula ({dp}); it counts as 0 here")
            vals = [v or 0 for v in vals]
        pts.append((float(kf.get("time", 0)), vals))
    pts.sort()
    if not pts:
        return None
    out = []
    for i in range(steps):
        t = length * i / steps if length > 0 else 0
        before = [p for p in pts if p[0] <= t]
        after = [p for p in pts if p[0] > t]
        a = before[-1] if before else (pts[-1][0] - length, pts[-1][1])
        b = after[0] if after else (pts[0][0] + length, pts[0][1])
        f = 0 if b[0] == a[0] else (t - a[0]) / (b[0] - a[0])
        out.append([a[1][k] + (b[1][k] - a[1][k]) * f for k in range(3)])
    return out


def fit(curve):
    """One wave per axis: mean, amplitude and phase of the loop's first harmonic."""
    n = len(curve)
    res = []
    for k in range(3):
        ys = [c[k] for c in curve]
        mean = sum(ys) / n
        a = 2 / n * sum((ys[i] - mean) * math.sin(2 * math.pi * i / n) for i in range(n))
        b = 2 / n * sum((ys[i] - mean) * math.cos(2 * math.pi * i / n) for i in range(n))
        res.append((mean, math.hypot(a, b), math.atan2(b, a)))
    return res


def motion_for(bb, group_uuid):
    """{state: {speed, phase, base, amp, move}} from the idle / walk / sneak animations, in bone space."""
    states = {}
    for anim in bb.get("animations") or []:
        key = (anim.get("name") or "").lower().split(".")[-1]
        if key not in STATES:
            continue
        animator = (anim.get("animators") or {}).get(group_uuid)
        if not animator:
            continue
        length = float(anim.get("length") or 0) or 1.0
        speed = 2 * math.pi / (length * 20)  # one loop per animation length, in game ticks
        kfs = animator.get("keyframes") or []
        rot = channel_curve([k for k in kfs if k.get("channel") == "rotation"], length)
        pos = channel_curve([k for k in kfs if k.get("channel") == "position"], length)
        rf = fit(rot) if rot else [(0, 0, 0)] * 3
        pf = fit(pos) if pos else [(0, 0, 0)] * 3
        # one phase per part: the biggest swing leads, the other axes follow it in or out of step
        lead = max(rf + pf, key=lambda w: w[1])
        phase = lead[2]
        signed = lambda w: w[1] * math.cos(w[2] - phase)
        # Blockbench → bone space flips y: rotations about x and z change sign, positions along y
        sx, sy, sz = -1, 1, -1
        states[STATES[key]] = {
            "speed": round(speed, 4), "phase": round(phase, 3),
            "base": [round(rf[0][0] * sx, 2), round(rf[1][0] * sy, 2), round(rf[2][0] * sz, 2)],
            "amp": [round(signed(rf[0]) * sx, 2), round(signed(rf[1]) * sy, 2), round(signed(rf[2]) * sz, 2)],
            "move": [round(signed(pf[0]), 3), round(-signed(pf[1]), 3), round(signed(pf[2]), 3)],
        }
    if not states:
        return None
    m = dict(states.get(None) or {"speed": next(iter(states.values()))["speed"], "base": [0, 0, 0], "amp": [0, 0, 0], "move": [0, 0, 0]})
    for st in ("run", "sneak"):
        if st in states:
            m[st] = states[st]
    return m


def is_rigged(bb):
    names = []

    def walk(n):
        for c in n["children"]:
            names.append(c["name"])
            walk(c)
    walk(outline(bb))
    has_tags = any(re.search(r"[#@]", n or "") for n in names)
    has_anims = any((a.get("name") or "").lower().split(".")[-1] in STATES for a in bb.get("animations") or [])
    return has_tags or has_anims or any((n or "").lower() in REFERENCE for n in names)


def rig_item(bb, sub, item_id):
    sys.path.insert(0, HERE)
    import animated
    cubes, textures = cube_list(bb)
    tree = outline(bb)
    ref = next((c for c in tree["children"] if c["name"].lower() in REFERENCE), None)
    ref_cubes = set()

    def all_cubes(n):
        return n["cubes"] + [u for c in n["children"] for u in all_cubes(c)]
    if ref:
        ref_cubes = set(all_cubes(ref))
        tree["children"] = [c for c in tree["children"] if c is not ref]
    cubes = [c for c in cubes if c[0] not in ref_cubes]
    if not cubes:
        sys.exit("no cubes in the model (besides the reference)")
    by_uuid = {c[0]: c for c in cubes}
    v = voxel_size(cubes)
    gl, dims = bounds(cubes, v)
    W, H, D = (n * v for n in dims)
    if ref:
        # The template's player: feet at 0, neck at 24 → bone space (y down from the neck).
        origin = [gl[0], 24 - gl[1], gl[2]]
    else:
        origin = ORIGINS[sub["slot"]](W, H, D)
    origin = [round(o, 4) for o in origin]
    to_bone = lambda p: [origin[0] + (p[0] - gl[0]), origin[1] - (p[1] - gl[1]), origin[2] + (p[2] - gl[2])]

    def cells(uuids):
        return sample_cells([by_uuid[u] for u in uuids if u in by_uuid], textures, gl, dims, v)

    # Groups that neither move, swap frames nor hide fold into their parent (or the body).
    def plain(n):
        m = NAME_RE.match(n["name"] or "")
        return not motion_for(bb, n["uuid"]) and not (m and (m["frame"] or m["when"]))

    raw = {}  # colour cells per sculpt key, palette later
    parts = []
    body_uuids = list(tree["cubes"])

    def visit(n, parent_name, sink):
        m = NAME_RE.match(n["name"] or "") or NAME_RE.match("part")
        if plain(n):
            sink.extend(all_cubes(n))
            return
        base = re.sub(r"[^a-z0-9_]", "_", m["name"].lower())[:24] or "part"
        own = list(n["cubes"])
        entry = next((p for p in parts if p["name"] == base and m["frame"]), None)
        if entry is None:
            entry = {"name": base, "pivot": to_bone(n["origin"]), "parent": parent_name, "when": m["when"],
                     "frames": [], "motion": motion_for(bb, n["uuid"])}
            parts.append(entry)
        frame = {"ticks": int(m["ticks"] or 20), "uuids": own, "order": int(m["frame"] or 1)}
        entry["frames"].append(frame)
        for c in n["children"]:
            visit(c, base, frame["uuids"])

    for c in tree["children"]:
        visit(c, None, body_uuids)

    body_vox = cells(body_uuids)
    for p in parts:
        p["frames"].sort(key=lambda f: f["order"])
        for f in p["frames"]:
            f["vox"] = cells(f["uuids"])
    colours = set(body_vox.values()) | {c for p in parts for f in p["frames"] for c in f["vox"].values()}
    allc = sorted(colours)
    q, chars = palette_of(dict(enumerate(allc)))  # ≤ 52 colours across every part and frame
    lookup = {allc[i]: chars[q[i]] for i in range(len(allc))}

    def sculpt(vox):
        s = animated.Sculpt(v, origin)
        s.cells = {k: lookup[c] for k, c in vox.items()}
        return s

    rig_parts = []
    for p in parts:
        frames = [(f["ticks"], sculpt(f["vox"])) for f in p["frames"]]
        if not any(s.cells for _, s in frames):
            continue
        part = animated.Part(p["name"], p["pivot"], frames, p["motion"], parent=p["parent"], when=p["when"])
        rig_parts.append(part)
    model = animated.model_json(v, origin, sculpt(body_vox) if body_vox else None, rig_parts)
    for part in model["rig"]["parts"]:
        part["order"] = "zyx"
    palette = {ch: "FF%02X%02X%02X" % c for c, ch in chars.items()}
    item = {
        "id": item_id, "kind": sub["slot"], "name": sub["title"][:40],
        "description": f"Made by {sub['creator']}." if not sub.get("notes") else sub["notes"][:120],
        "availability": "free", "author": sub["creator"], "palette": palette, "model": model,
    }
    return item, None


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
    save_preview(item, preview, HERE)
    print("next: python3 build_catalog.py → deploy the Worker →", f"python3 creations.py publish {sid} {item_id}")


def save_preview(item, preview, where):
    try:
        sys.path.insert(0, HERE)
        if "rig" in item["model"]:
            import animated
            path = os.path.join(where, f"preview-{item['id']}.gif")
            animated.gif(item, path)
            n = len(item["model"]["rig"]["parts"])
            print(f"animated: {n} parts — " + ", ".join(p["name"] + (f" ({len(p['frames'])} frames)" if "frames" in p else "")
                                                        + (f" @{p['when']}" if "when" in p else "") for p in item["model"]["rig"]["parts"]))
        else:
            import models
            path = os.path.join(where, f"preview-{item['id']}.png")
            models.render({**item, "_vox": preview}).save(path)
        print(f"preview {os.path.relpath(path)}")
    except Exception as e:  # the preview is a convenience
        print(f"(no preview: {e})")


def cmd_try(path, slot, out):
    with open(path) as f:
        bb = json.load(f)
    name = os.path.splitext(os.path.basename(path))[0]
    item_id = re.sub(r"[^a-z0-9_]+", "_", name.lower()).strip("_")[:32] or "model"
    item, preview = to_item(bb, {"slot": slot, "title": name, "creator": "you"}, item_id)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, f"{item_id}.json"), "w") as f:
        json.dump(item, f, indent=2)
    print(f"{item_id}: {len(item['palette'])} colours, voxel {item['model']['voxel']}")
    save_preview(item, preview, out)


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
    out = HERE
    if "--out" in args:
        i = args.index("--out")
        out = args[i + 1]
        del args[i:i + 2]
    if not args or args[0] not in ("list", "pull", "publish", "try"):
        sys.exit(__doc__)
    if args[0] == "try":
        cmd_try(args[1], args[2], out)
        sys.exit()
    if args[0] == "list":
        cmd_list(local, args[1] if len(args) > 1 else "pending")
    elif args[0] == "pull":
        cmd_pull(local, args[1], item)
    else:
        cmd_publish(local, args[1], args[2])
