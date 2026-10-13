"""Writes BashCraft's brand images from Minecraft's own textures and font.

    python3 branding/generate.py              # (re)write the images
    python3 branding/generate.py --check      # exit 1 if a committed image differs from a fresh render
    python3 branding/generate.py --jar PATH   # use a specific Minecraft 26.3 client jar
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import assets  # noqa: E402
from draw import Font  # noqa: E402
from jar import Assets, find_jar  # noqa: E402
from png import encode, upscale  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# (output path relative to the repo root, image function, scale)
OUTPUTS = [
    ("src/main/resources/assets/bashcraft/icon.png", assets.icon, 4),
    ("docs/images/brand/icon.png", assets.icon, 8),
    ("docs/images/brand/banner.png", assets.banner, 2),
    ("docs/images/brand/featured.png", assets.featured, 2),
    ("docs/images/brand/gallery-cave.png", assets.gallery_cave, 2),
]


def render_all(jar_path):
    a = Assets(jar_path)
    font = Font(a)
    return {path: encode(upscale(fn(a, font), k)) for path, fn, k in OUTPUTS}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jar", help="Minecraft 26.3 client jar (default: Loom's cache)")
    parser.add_argument("--check", action="store_true", help="compare with the committed images instead of writing")
    parser.add_argument("--root", default=ROOT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    rendered = render_all(args.jar or find_jar(args.root))
    stale = []
    for path, data in rendered.items():
        target = os.path.join(args.root, path)
        if args.check:
            if not os.path.exists(target) or open(target, "rb").read() != data:
                stale.append(path)
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as f:
            f.write(data)
        print("wrote", path)
    if stale:
        print("out of date:", ", ".join(stale))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
