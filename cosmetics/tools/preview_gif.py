"""Joins the in-game screenshots (front_NNN_<move>.png, back_NNN_<move>.png) into one GIF, front and back side by side.
Used by preview-in-game.sh.  python3 preview_gif.py <shots dir> <out.gif> <label>"""
import glob, os, sys
from PIL import Image, ImageDraw

shots, out, label = sys.argv[1:4]
front = sorted(glob.glob(os.path.join(shots, "front_*.png")))
back = sorted(glob.glob(os.path.join(shots, "back_*.png")))
if not front:
    sys.exit(f"no screenshots in {shots}: did the game run?")


def crop(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size  # the player stands in the middle; keep the middle 60%
    return im.crop((int(w * .2), int(h * .15), int(w * .8), int(h * .75)))


frames = []
for f, b in zip(front, back or front):
    a, c = crop(f), crop(b)
    sheet = Image.new("RGB", (a.width * 2, a.height))
    sheet.paste(a, (0, 0)); sheet.paste(c, (a.width, 0))
    move = os.path.basename(f).rsplit("_", 1)[-1].removesuffix(".png")
    d = ImageDraw.Draw(sheet)
    d.text((8, 6), label, fill="white")
    d.text((8, a.height - 16), move, fill="white")
    frames.append(sheet)
frames[0].save(out, save_all=True, append_images=frames[1:], duration=50, loop=0)
