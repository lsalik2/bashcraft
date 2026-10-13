"""Pixel compositing and Minecraft's bitmap font."""
from png import CLEAR


def canvas(w, h, fill=CLEAR):
    return [[fill] * w for _ in range(h)]


def over(dst, src):
    """src drawn over dst (straight alpha)."""
    a = src[3] / 255
    if a >= 1:
        return src
    b = dst[3] / 255
    out_a = a + b * (1 - a)
    if out_a == 0:
        return CLEAR
    mix = lambda i: int((src[i] * a + dst[i] * b * (1 - a)) / out_a)
    return (mix(0), mix(1), mix(2), int(out_a * 255))


def paste(dst, img, x0, y0):
    for y, row in enumerate(img):
        for x, p in enumerate(row):
            if 0 <= y0 + y < len(dst) and 0 <= x0 + x < len(dst[0]) and p[3]:
                dst[y0 + y][x0 + x] = over(dst[y0 + y][x0 + x], p)


def rect(dst, x0, y0, w, h, color):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            dst[y][x] = over(dst[y][x], color)


def dim(p, k):
    return (int(p[0] * k), int(p[1] * k), int(p[2] * k), 255)


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


class Font:
    """Minecraft's font: textures/font/ascii.png laid out per font/include/default.json (8x8 cells)."""

    def __init__(self, assets):
        sheet = assets.texture("font/ascii")
        provider = next(p for p in assets.json("font/include/default")["providers"] if p.get("file") == "minecraft:font/ascii.png")
        self.glyphs = {}
        for row, chars in enumerate(provider["chars"]):
            for col, ch in enumerate(chars):
                if ch == "\x00":
                    continue
                cell = [[sheet[row * 8 + y][col * 8 + x][3] > 0 for x in range(8)] for y in range(8)]
                used = [x for x in range(8) if any(cell[y][x] for y in range(8))]
                w = (max(used) + 1) if used else 3   # a blank glyph (space) advances 4, like vanilla
                self.glyphs[ch] = ["".join("X" if cell[y][x] else "." for x in range(w)) for y in range(8)]

    def width(self, s, k=1):
        return sum((len(self.glyphs[c][0]) + 1) * k for c in s) - k

    def ink_rows(self, s):
        rows = [y for y in range(8) if any("X" in self.glyphs[c][y] for c in s)]
        return min(rows), max(rows)

    def draw(self, img, s, x, y, colors, k=1, shadow_divisor=16):
        """Draws s with font row 0 at y; colors is one colour or one per character. shadow_divisor None: no shadow."""
        for i, ch in enumerate(s):
            color = colors[i] if isinstance(colors, list) else colors
            g = [''.join(c * k for c in r) for r in self.glyphs[ch] for _ in range(k)]
            if shadow_divisor:
                d = shadow_divisor
                self._glyph(img, g, x + k, y + k, (color[0] // d, color[1] // d, color[2] // d, color[3]))
            self._glyph(img, g, x, y, color)
            x += (len(self.glyphs[ch][0]) + 1) * k
        return x

    @staticmethod
    def _glyph(img, rows, x0, y0, color):
        for y, row in enumerate(rows):
            for x, c in enumerate(row):
                if c == "X":
                    img[y0 + y][x0 + x] = color
