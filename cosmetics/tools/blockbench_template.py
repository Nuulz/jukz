"""Writes the Blockbench template the guide (nuulm.com/jukz/guia) offers for download: a reference player
(group "reference", left out when converting) and an example animated cosmetic that uses every feature:
moving parts (idle / walk / sneak), a part riding on another, and blinking frames.

    python3 blockbench_template.py out.bbmodel
"""
import base64, io, json, sys, uuid
from PIL import Image

COLOURS = [  # one 1×1 swatch each on a 16×16 texture
    ("skin", (214, 166, 128)), ("hair", (74, 50, 32)), ("shirt", (60, 160, 160)), ("pants", (60, 70, 160)),
    ("band", (91, 155, 255)), ("stalk", (40, 44, 64)), ("light", (255, 213, 74)), ("glow", (255, 250, 220)),
    ("eye", (40, 40, 60)), ("band_dark", (47, 100, 196)),
]
SWATCH = {name: i for i, (name, _) in enumerate(COLOURS)}


def texture():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for i, (_, c) in enumerate(COLOURS):
        img.putpixel((i, 0), c + (255,))
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def uid():
    return str(uuid.uuid4())


elements = []


def cube(name, fr, to, colour, faces_colour=None):
    """A cube painted one colour (faces_colour overrides single faces, e.g. eyes on the north face)."""
    u = uid()
    faces = {}
    for f in ("north", "east", "south", "west", "up", "down"):
        c = SWATCH[(faces_colour or {}).get(f, colour)]
        faces[f] = {"uv": [c, 0, c + 1, 1], "texture": 0}
    elements.append({"name": name, "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
                     "allow_mirror_modeling": True, "from": fr, "to": to, "autouv": 0, "color": 0,
                     "origin": [(a + b) / 2 for a, b in zip(fr, to)], "faces": faces, "type": "cube", "uuid": u})
    return u


def group(name, origin, children, locked=False):
    return {"name": name, "origin": origin, "color": 0, "uuid": uid(), "export": True, "mirror_uv": False,
            "isOpen": True, "locked": locked, "visibility": True, "autouv": 0, "children": children}


def keyframes(channel, points):
    return [{"channel": channel, "data_points": [{"x": str(x), "y": str(y), "z": str(z)}], "uuid": uid(), "time": t,
             "color": -1, "interpolation": "linear"} for t, (x, y, z) in points]


def build():
    # The player, as in Minecraft: feet at y 0, neck at 24, face to the north (-z). Don't move it.
    reference = group("reference", [0, 24, 0], [
        cube("head", [-4, 24, -4], [4, 32, 4], "skin", {"north": "skin", "up": "hair"}),
        cube("eye_l", [-3, 27, -4.01], [-1, 28, -4], "eye"),
        cube("eye_r", [1, 27, -4.01], [3, 28, -4], "eye"),
        cube("body", [-4, 12, -2], [4, 24, 2], "shirt"),
        cube("arm_r", [-8, 12, -2], [-4, 24, 2], "skin"),
        cube("arm_l", [4, 12, -2], [8, 24, 2], "skin"),
        cube("leg_r", [-4, 0, -2], [0, 12, 2], "pants"),
        cube("leg_l", [0, 0, -2], [4, 12, 2], "pants"),
    ], locked=True)
    # The example: a headband (no group: it stays still) and two antennas that sway, with blinking tips.
    band = [cube("band", [-4.5, 30, -4.5], [4.5, 31.5, 4.5], "band", {"up": "band_dark"})]
    antennas = []
    for side, x in (("l", -2.5), ("r", 2.5)):
        tip_on = cube(f"tip_{side}_on", [x - 1, 36.5, -1], [x + 1, 38.5, 1], "light")
        tip_off = cube(f"tip_{side}_glow", [x - 1, 36.5, -1], [x + 1, 38.5, 1], "glow")
        stalk = cube(f"stalk_{side}", [x - 0.5, 31.5, -0.5], [x + 0.5, 36.5, 0.5], "stalk")
        antennas.append(group(f"antenna_{side}", [x, 31.5, 0], [
            stalk,
            group(f"tip_{side}#1=40", [x, 36.5, 0], [tip_on]),
            group(f"tip_{side}#2=6", [x, 36.5, 0], [tip_off]),
        ]))
    outliner = [reference] + band + antennas

    def anim(name, length, per_side):
        animators = {}
        for g, sign in ((antennas[0], 1), (antennas[1], -1)):
            animators[g["uuid"]] = {"name": g["name"], "type": "bone", "keyframes": per_side(sign)}
        return {"uuid": uid(), "name": name, "loop": "loop", "override": False, "length": length, "snapping": 20,
                "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "",
                "animators": animators}

    animations = [
        # standing: a slow sway, the two antennas mirrored
        anim("idle", 2, lambda s: keyframes("rotation", [(0, (0, 0, 0)), (0.5, (0, 0, 10 * s)), (1, (0, 0, 0)), (1.5, (0, 0, -10 * s)), (2, (0, 0, 0))])),
        # walking: faster and wider
        anim("walk", 0.5, lambda s: keyframes("rotation", [(0, (0, 0, 0)), (0.125, (0, 0, 22 * s)), (0.25, (0, 0, 0)), (0.375, (0, 0, -22 * s)), (0.5, (0, 0, 0))])),
        # sneaking: bent back, barely moving
        anim("sneak", 1, lambda s: keyframes("rotation", [(0, (-35, 0, 3 * s)), (0.5, (-35, 0, -3 * s)), (1, (-35, 0, 3 * s))])),
    ]
    return {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": "jukz-template", "model_identifier": "", "visible_box": [1, 1, 0], "variable_placeholders": "",
        "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
        "resolution": {"width": 16, "height": 16},
        "elements": elements, "outliner": outliner,
        "textures": [{"path": "", "name": "palette.png", "folder": "", "namespace": "", "id": "0", "width": 16, "height": 16,
                      "uv_width": 16, "uv_height": 16, "particle": False, "use_as_default": False, "layers_enabled": False,
                      "sync_to_project": "", "render_mode": "default", "render_sides": "auto", "frame_time": 1,
                      "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False, "visible": True,
                      "internal": True, "saved": False, "uuid": uid(), "source": texture()}],
        "animations": animations,
    }


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "jukz-template.bbmodel"
    with open(out, "w") as f:
        json.dump(build(), f, indent=1)
    print(out)
