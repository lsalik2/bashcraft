"""python3 -m unittest discover -s docs/images/brand/generator -v"""
import hashlib
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import assets  # noqa: E402
import generate  # noqa: E402
from draw import Font  # noqa: E402
from jar import Assets, find_jar  # noqa: E402
from png import decode, encode, upscale  # noqa: E402

# SHA-256 of each image's logical pixels (RGBA, row by row), taken from the renders approved on 2026-10-13
# (and the featured book's middle recipe later changed from "Feed everyone" to "Who's online").
APPROVED = {
    "icon": ("0ab1a944f1f0ebfbc84ec67bbf051b02d2f8b999b434af886b0e4a0388dd40da", 48, 48),
    "banner": ("e4f989d63ed685dfe188f071b1695622f90c55e3c6e5ddaa9857cda8dd5664a8", 480, 192),
    "featured": ("b95178795357fbb8ea4479735ac2a47b5aa076e723a42a0c79c8000241a76353", 512, 288),
    "gallery_cave": ("75dd9e5f513e068513a09db839b3067287fca85bdbd646bbf322a3568e4e7e52", 512, 288),
}


def digest(rows):
    return hashlib.sha256(b"".join(bytes(p) for row in rows for p in row)).hexdigest()


class BrandingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jar = find_jar(generate.ROOT)
        cls.assets = Assets(cls.jar)
        cls.font = Font(cls.assets)

    def test_images_match_the_approved_renders(self):
        for name, (sha, w, h) in APPROVED.items():
            with self.subTest(name):
                img = getattr(assets, name)(self.assets, self.font)
                self.assertEqual((w, h), (len(img[0]), len(img)))
                self.assertEqual(sha, digest(img))

    def test_png_round_trip_and_packed_palettes(self):
        rows = [[(1, 2, 3, 4), (5, 6, 7, 8)], [(9, 10, 11, 12), (13, 14, 15, 255)]]
        self.assertEqual(rows, decode(encode(rows)))
        dirt = self.assets.texture("block/dirt")      # a 4-bit palette PNG in the jar
        self.assertEqual((16, 16), (len(dirt[0]), len(dirt)))
        self.assertEqual(upscale(rows, 2)[3][3], rows[1][1])

    def test_generate_writes_every_output_and_check_finds_stale_files(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(0, generate.main(["--jar", self.jar, "--root", root]))
            sizes = {path: decode(open(os.path.join(root, path), "rb").read()) for path, _, _ in generate.OUTPUTS}
            self.assertEqual((192, 192), (len(sizes["src/main/resources/assets/bashcraft/icon.png"][0]), len(sizes["src/main/resources/assets/bashcraft/icon.png"])))
            self.assertEqual((384, 384), (len(sizes["docs/images/brand/icon.png"][0]), len(sizes["docs/images/brand/icon.png"])))
            self.assertEqual((960, 384), (len(sizes["docs/images/brand/banner.png"][0]), len(sizes["docs/images/brand/banner.png"])))
            self.assertEqual(0, generate.main(["--jar", self.jar, "--root", root, "--check"]))
            open(os.path.join(root, "docs/images/brand/banner.png"), "wb").write(b"stale")
            self.assertEqual(1, generate.main(["--jar", self.jar, "--root", root, "--check"]))


if __name__ == "__main__":
    unittest.main()
