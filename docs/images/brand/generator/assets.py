"""The four BashCraft brand images, at their logical (1x) size."""
from draw import Font, canvas, dim, hexc, paste, rect
from png import upscale

WHITE, GRAY, AQUA = hexc("#ffffff"), hexc("#aaaaaa"), hexc("#55ffff")
DARK_AQUA, YELLOW, HEADING = hexc("#00aaaa"), hexc("#ffff55"), hexc("#555555")
BLOCK, TEXEL = 64, 4
# The chat line in the banner and gallery: a real chain in the client companion's colours.
CHAIN = [("/sh ", GRAY), ("tp ", GRAY), ("@s ", AQUA), ("~ ~9 ~ ", YELLOW), ("&& ", DARK_AQUA),
         ("sleep 2s ", GRAY), ("; ", DARK_AQUA), ("echo hi", GRAY)]
BANNER_ORES = {(0, 0): "coal_ore", (5, 0): "iron_ore", (6, 0): "copper_ore", (4, 1): "gold_ore",
               (2, 2): "redstone_ore", (7, 2): "diamond_ore"}
GALLERY_ORES = {(1, 0): "coal_ore", (7, 1): "coal_ore", (0, 2): "copper_ore", (2, 2): "iron_ore", (1, 3): "iron_ore",
                (4, 3): "gold_ore", (2, 4): "diamond_ore", (4, 4): "redstone_ore"}
BOOK_LINES = [("Village trip", HEADING), ("locate structure", None), ("  #village |", None), ("surface | tp @s", None),
              ("", None), ("Who's online", HEADING), ("list | each echo", None), ("  {value} is here", None),
              ("", None), ("Quick home", HEADING), ("alias home =", None), ("  tp @s 0 80 0", None)]


def block_wall(assets, w, h, brightness, cells=None, grid_x=0, grid_y=0):
    """A wall of 16-texel blocks, 4 px per texel; cells maps (column, row) to another block's texture."""
    cells = cells or {}
    return [[dim(_tex(assets, cells.get(((x - grid_x) // BLOCK, (y - grid_y) // BLOCK), "stone"))
                 [((y - grid_y) % BLOCK) // TEXEL][((x - grid_x) % BLOCK) // TEXEL], brightness)
             for x in range(w)] for y in range(h)]


_cache = {}


def _tex(assets, name):
    if name not in _cache:
        _cache[name] = assets.texture(f"block/{name}")[:16]
    return _cache[name]


def tiled(assets, name, w, h, brightness):
    tile = _tex(assets, name)
    return [[dim(tile[(y // TEXEL) % 16][(x // TEXEL) % 16], brightness) for x in range(w)] for y in range(h)]


def icon(assets, font):
    """48x48: '/sh' over '&&_' on menu dirt (×0.45), ink block exactly centred."""
    n, k, gap = 48, 2, 6
    img = tiled(assets, "dirt", n, n, 0.45)
    (t1, b1), (t2, b2) = font.ink_rows("/sh"), font.ink_rows("&&_")
    h1, h2 = (b1 - t1 + 1) * k, (b2 - t2 + 1) * k
    w = font.width("/sh", k)
    x0, y0 = (n - w) // 2, (n - (h1 + gap + h2)) // 2
    font.draw(img, "/sh", x0, y0 - t1 * k, WHITE, k)
    font.draw(img, "&&_", x0, y0 + h1 + gap - t2 * k, [AQUA, AQUA, WHITE], k)
    return img


def tile(img, image, x, y):
    """The icon as a tile: a 4 px drop shadow, a 2 px dark outline, then the image."""
    n = len(image)
    rect(img, x + 4, y + 4, n, n, (0, 0, 0, 120))
    rect(img, x - 2, y - 2, n + 4, n + 4, (0, 0, 0, 200))
    paste(img, image, x, y)


def chat_line(img, font, w, y):
    text = "".join(t for t, _ in CHAIN)
    tw = font.width(text, 2)
    rect(img, (w - tw) // 2 - 8, y, tw + 16, 28, (0, 0, 0, 150))
    x = font.draw(img, text, (w - tw) // 2, y + 7, [c for t, c in CHAIN for _ in t], 2)
    rect(img, x + 2, y + 7 + 14, 10, 2, WHITE)


def banner(assets, font):
    """480x192: stone wall with ores; icon tile and wordmark 16 px from the top, chat line below."""
    w, h, wk, top = 480, 192, 5, 16
    img = block_wall(assets, w, h, 0.5, BANNER_ORES, grid_x=-16)
    row_w = 48 + 18 + font.width("BashCraft", wk)
    x0 = (w - row_w) // 2
    tile(img, icon(assets, font), x0, top)
    font.draw(img, "BashCraft", x0 + 66, top + (48 - 7 * wk) // 2, WHITE, wk)
    chat_line(img, font, w, top + 78)
    return img


def gallery_cave(assets, font):
    """512x288: the banner's style stacked — icon tile 2x, wordmark 6x, chat line."""
    w, h, wk, top = 512, 288, 6, 22
    img = block_wall(assets, w, h, 0.5, GALLERY_ORES, grid_y=-16)
    tile(img, upscale(icon(assets, font), 2), (w - 96) // 2, top)
    word_y = top + 96 + 20
    font.draw(img, "BashCraft", (w - font.width("BashCraft", wk)) // 2, word_y, WHITE, wk)
    chat_line(img, font, w, word_y + 7 * wk + 20)
    return img


def featured(assets, font):
    """512x288: a book-and-quill page of recipes on spruce planks, the brand block beside it."""
    w, h, gap, wk = 512, 288, 40, 4
    img = tiled(assets, "spruce_planks", w, h, 0.85)
    page = [row[20:166] for row in assets.texture("gui/book")[1:181]]
    pw, ph = 146, 180
    word_w = font.width("BashCraft", wk)
    brand_w, brand_h = max(96, word_w), 96 + 18 + 7 * wk
    gx, by = (w - (pw + gap + brand_w)) // 2, (h - ph) // 2
    rect(img, gx + 4, by + 4, pw, ph, (0, 0, 0, 110))
    paste(img, page, gx, by)
    ink = hexc("#000000")
    font.draw(img, "Page 1 of 1", gx + pw - 12 - font.width("Page 1 of 1"), by + 15, ink, shadow_divisor=None)
    for i, (s, color) in enumerate(BOOK_LINES):
        font.draw(img, s, gx + 16, by + 30 + i * 9, color or ink, shadow_divisor=None)
    rx, ry = gx + pw + gap, by + (ph - brand_h) // 2
    tile(img, upscale(icon(assets, font), 2), rx + (brand_w - 96) // 2, ry)
    font.draw(img, "BashCraft", rx + (brand_w - word_w) // 2, ry + 96 + 18, WHITE, wk)
    return img
