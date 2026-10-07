"""Rebuilds ../catalog.json from the ASCII sources in badges.py (tab-list badges) and models.py (3D
pieces). Edit the art there, then:

    python3 build_catalog.py            # writes ../catalog.json
    python3 badges.py                   # previews: ./badges1.png, ./badges2.png
    python3 models.py models.png        # isometric previews of the 3D pieces on a reference head/body
    python3 animated.py out/            # animated pieces (rigs): a GIF each, idle / walking / sneaking
    python3 creations.py --help         # community models from nuulm.com/jukz/crear → ../creations/*.json

The catalog is what ships: the Worker serves it and the mod bundles it. `npm test` in rendezvous-worker
and the fabric tests validate it.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import badges  # noqa: E402
import models  # noqa: E402
import animated  # noqa: E402

items = badges.build() + [{k: v for k, v in it.items() if k != "_vox"} for it in models.build()] + animated.build()
# Community items from the creators page (creations.py pull writes them), in a stable order.
creations = os.path.join(HERE, "..", "creations")
if os.path.isdir(creations):
    for name in sorted(os.listdir(creations)):
        if name.endswith(".json"):
            with open(os.path.join(creations, name)) as f:
                items.append(json.load(f))
catalog = {"version": 2, "defaultBadge": "jukz", "items": items}
with open(os.path.join(HERE, "..", "catalog.json"), "w") as f:
    f.write(json.dumps(catalog, indent=2) + "\n")
print(f"catalog.json: {len(items)} items")
