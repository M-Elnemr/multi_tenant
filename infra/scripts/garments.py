"""Clean flat product pictures for the fashion demo shop: one silhouette per product type, drawn in the product's colour (no photos of people, no brands)."""
import io, math
from PIL import Image, ImageDraw, ImageFilter

RGB = {"أسود": (38, 38, 42), "أبيض": (246, 246, 242), "رمادي": (150, 152, 158), "بيج": (214, 196, 166), "بني": (122, 82, 52), "أحمر": (196, 40, 46),
       "وردي": (236, 160, 184), "برتقالي": (238, 128, 40), "أصفر": (240, 200, 60), "أخضر": (56, 140, 80), "زيتي": (104, 112, 64), "تركواز": (40, 176, 180),
       "أزرق": (44, 92, 176), "كحلي": (32, 44, 82), "سماوي": (140, 196, 232), "بنفسجي": (124, 80, 168), "ذهبي": (212, 172, 72), "فضي": (192, 196, 204), "متعدد": None}
S = 2000   # drawn at twice the size, then reduced for smooth edges


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def outline(c):
    return shade(c, 0.62) if sum(c) > 160 else shade(c, 1.9) if sum(c) < 120 else shade(c, 0.7)


def P(*pts):
    return [(x * S / 100, y * S / 100) for x, y in pts]


# silhouettes on a 100 x 100 grid: list of (polygon, part) — part "body" uses the colour, "dark"/"light" a shade of it
def tshirt(long=False, v=False):
    sl = [(8, 30 if not long else 78), (20, 33 if not long else 80)]
    body = P((35, 16), (42, 14), (50, 18), (58, 14), (65, 16), (88, 26), (92 if long else 82, 36 if not long else 76), (80 if long else 72, 40 if not long else 78),
             (70, 32), (70, 86), (30, 86), (30, 32), (20 if long else 28, 40 if not long else 78), (8 if long else 18, 36 if not long else 76), (12, 26))
    neck = P((42, 14), (50, 26 if v else 20), (58, 14), (54, 13), (50, 17 if not v else 22), (46, 13))
    return [(body, "body"), (neck, "dark")]


def shirt(long=True):
    shapes = tshirt(long)
    collar = P((40, 13), (50, 22), (44, 26)), P((60, 13), (50, 22), (56, 26))
    shapes = [shapes[0], (collar[0], "light"), (collar[1], "light")]
    shapes += [(P((49.4, 22), (50.6, 22), (50.6, 86), (49.4, 86)), "dark")]
    shapes += [(P((49, y), (51, y), (51, y + 2), (49, y + 2)), "light") for y in (32, 44, 56, 68)]
    return shapes


def hoodie():
    body = P((36, 18), (64, 18), (86, 28), (94, 76), (82, 80), (72, 38), (72, 88), (28, 88), (28, 38), (18, 80), (6, 76), (14, 28))
    hood = P((36, 18), (40, 6), (60, 6), (64, 18), (56, 26), (44, 26))
    pocket = P((36, 64), (64, 64), (68, 80), (32, 80))
    return [(body, "body"), (hood, "dark"), (pocket, "dark")]


def jacket(long=False):
    bottom = 94 if long else 84
    body = P((36, 14), (64, 14), (86, 24), (94, 80), (82, 82), (72, 38), (72, bottom), (28, bottom), (28, 38), (18, 82), (6, 80), (14, 24))
    lapel = P((36, 14), (50, 40), (64, 14), (58, 14), (50, 30), (42, 14))
    return [(body, "body"), (lapel, "dark"), (P((49.5, 30), (50.5, 30), (50.5, bottom), (49.5, bottom)), "dark")]


def puffer():
    shapes = jacket()
    shapes += [(P((28, y), (72, y), (72, y + 0.8), (28, y + 0.8)), "dark") for y in (30, 44, 58, 72)]
    return shapes


def dress(maxi=False, flare=True):
    hem = 94 if maxi else 82
    body = P((38, 10), (44, 10), (50, 16), (56, 10), (62, 10), (64, 24), (60, 40), (84 if flare else 68, hem), (16 if flare else 32, hem), (40, 40), (36, 24))
    return [(body, "body"), (P((40, 38), (60, 38), (60.6, 42), (39.4, 42)), "dark")]


def abaya():
    body = P((38, 8), (62, 8), (82, 20), (96, 60), (84, 64), (74, 36), (80, 96), (20, 96), (26, 36), (16, 64), (4, 60), (18, 20))
    return [(body, "body"), (P((49.5, 12), (50.5, 12), (50.5, 96), (49.5, 96)), "dark")]


def skirt():
    return [(P((34, 20), (66, 20), (84, 84), (16, 84)), "body"), (P((34, 20), (66, 20), (66, 26), (34, 26)), "dark")]


def pants(short=False):
    b = 54 if short else 92
    body = P((28, 10), (72, 10), (76, b), (54, b), (50, 34), (46, b), (24, b))
    return [(body, "body"), (P((28, 10), (72, 10), (72, 15), (28, 15)), "dark")]


def onesie():
    body = P((38, 12), (62, 12), (84, 24), (80, 38), (68, 32), (70, 70), (62, 90), (54, 90), (50, 76), (46, 90), (38, 90), (30, 70), (32, 32), (20, 38), (16, 24))
    return [(body, "body"), (P((44, 12), (56, 12), (50, 20)), "light")]


def arc(cx, cy, rx, ry, a0, a1, n=10):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def sneaker(high=False):
    top = 30 if high else 46
    pts = [(12, 74), (11, top + 10), (14, top + 2), (30, top), (36, top + 4)] + [(44, 52), (58, 55)] + arc(80, 68, 13, 12, -90, 0) + [(93, 75), (12, 75)]
    body = P(*pts)
    sole = P((10, 74), (94, 74), (93, 80), *arc(88, 80, 5, 3, 0, 90, 4), (14, 83), (10, 80))
    toe = P(*arc(80, 68, 12, 10, -80, -5, 6), (80, 70))
    lace = [(P((38 + i * 6, 50 + i * 1.4), (44 + i * 6, 51 + i * 1.4), (43 + i * 6, 53 + i * 1.4), (37 + i * 6, 52 + i * 1.4)), "light") for i in range(3)]
    return [(body, "body"), (toe, "dark"), (sole, "light")] + lace


def heel():
    pts = [(16, 60), (22, 50), (32, 52), (44, 64), (60, 74), (78, 78)] + arc(86, 85, 7, 6, -90, 90, 6) + [(66, 91), (48, 86), (32, 74), (29, 74), (28, 93), (23, 93), (21, 72), (16, 68)]
    return [(P(*pts), "body"), (P((24, 74), (29, 74), (28, 93), (23, 93)), "dark")]


def sandal():
    sole = P((8, 70), (92, 66), (94, 76), (10, 80))
    straps = [P((24, 70), (34, 46), (40, 46), (32, 70)), P((50, 68), (60, 44), (66, 44), (58, 68)), P((30, 54), (66, 52), (66, 56), (30, 58))]
    return [(sole, "dark")] + [(s, "body") for s in straps]


def handbag(small=False):
    w = 26 if small else 36
    body = P((50 - w, 42), (50 + w, 42), (50 + w + 4, 86), (50 - w - 4, 86))
    return [(body, "body"), (P((50 - w, 42), (50 + w, 42), (50 + w, 52), (50 - w, 52)), "dark")], "handle"


def backpack():
    body = P((26, 22), (74, 22), (80, 90), (20, 90))
    return [(body, "body"), (P((32, 58), (68, 58), (70, 84), (30, 84)), "dark"), (P((26, 22), (74, 22), (74, 34), (26, 34)), "dark")], "loop"


def wallet():
    return [(P((18, 34), (82, 34), (82, 74), (18, 74)), "body"), (P((18, 34), (82, 34), (82, 48), (18, 48)), "dark"), (P((66, 44), (76, 44), (76, 52), (66, 52)), "light")]


def suitcase():
    return [(P((28, 22), (72, 22), (72, 86), (28, 86)), "body")] + [(P((x, 24), (x + 1.4, 24), (x + 1.4, 84), (x, 84)), "dark") for x in (38, 50, 62)] + \
           [(P((44, 10), (56, 10), (56, 22), (54, 22), (54, 13), (46, 13), (46, 22), (44, 22)), "dark"), (P((30, 86), (36, 86), (36, 92), (30, 92)), "dark"), (P((64, 86), (70, 86), (70, 92), (64, 92)), "dark")]


def belt():
    return [(P((6, 44), (94, 44), (94, 56), (6, 56)), "body"), (P((60, 40), (72, 40), (72, 60), (60, 60)), "light")]


def cap():
    return [(P((22, 62), (26, 40), (40, 28), (60, 28), (74, 40), (78, 62)), "body"), (P((70, 56), (96, 62), (94, 68), (70, 64)), "dark")]


def scarf():
    return [(P((30, 10), (70, 10), (70, 22), (56, 30), (60, 92), (46, 92), (44, 30), (30, 22)), "body")] + \
           [(P((46, y), (60, y), (60, y + 1.5), (46, y + 1.5)), "dark") for y in (80, 84, 88)]


def socks():
    a = P((22, 12), (40, 12), (40, 66), (52, 76), (48, 88), (22, 80))
    b = P((54, 12), (72, 12), (72, 66), (84, 76), (80, 88), (54, 80))
    return [(a, "body"), (b, "body"), (P((22, 12), (40, 12), (40, 18), (22, 18)), "dark"), (P((54, 12), (72, 12), (72, 18), (54, 18)), "dark")]


KIND = {
    "shirts-tops": lambda k: shirt() if any(w in k for w in ("oxford", "linen", "denim-shirt", "flannel", "dress-shirt", "boy-shirt", "oversized-shirt")) else
                             shirt(False) if "short-sleeve" in k else tshirt(long="long" in k or "henley" in k, v="vneck" in k),
    "activewear": lambda k: pants(short=True) if "shorts" in k else jacket() if "tracksuit" in k or "set" in k else tshirt(),
    "pants-jeans": lambda k: pants(short="shorts" in k), "dresses": lambda k: dress(maxi="maxi" in k or "evening" in k, flare="knit" not in k),
    "bridal-evening": lambda k: dress(maxi=True), "skirts": lambda k: skirt(), "abayas-modest": lambda k: abaya() if "set" not in k else jacket(long=True),
    "scarves-hijabs": lambda k: scarf(), "jackets-coats": lambda k: puffer() if "puffer" in k else jacket(long="coat" in k),
    "suits-blazers": lambda k: jacket(), "knitwear-hoodies": lambda k: hoodie() if "hoodie" in k else shirt() if "cardigan" in k else tshirt(long=True),
    "sleepwear": lambda k: abaya() if "robe" in k else shirt(), "swimwear": lambda k: pants(short=True) if "m-" in k else abaya(),
    "underwear-socks": lambda k: socks() if "socks" in k else pants(short=True), "traditional-wear": lambda k: abaya(),
    "outfit-sets": lambda k: tshirt(), "baby-clothing": lambda k: onesie(),
    "casual-shoes": lambda k: sneaker(), "sport-shoes": lambda k: sneaker(), "formal-shoes": lambda k: sneaker(), "boots": lambda k: sneaker(high=True),
    "heels-flats": lambda k: heel() if "heels" in k or "evening" in k else sneaker(), "sandals-slippers": lambda k: sandal(),
    "handbags": lambda k: handbag(small="crossbody" in k or "clutch" in k or "messenger" in k), "backpacks": lambda k: backpack(),
    "wallets": lambda k: wallet(), "travel-bags": lambda k: suitcase() if "suitcase" in k else handbag(), "belts": lambda k: belt(),
    "hats-caps": lambda k: cap(),
}


def picture(key, cat, colour):
    shapes = KIND[cat](key)
    extra = None
    if isinstance(shapes, tuple):
        shapes, extra = shapes
    base = RGB.get(colour)
    multi = base is None
    if multi:
        base = (90, 130, 190)
    bg = (244, 241, 236) if sum(base) < 600 else (226, 230, 236)
    im = Image.new("RGB", (S, S), bg)
    shadow = Image.new("L", (S, S), 0)
    ImageDraw.Draw(shadow).ellipse([S * .18, S * .88, S * .82, S * .96], fill=70)
    im.paste(shade(bg, 0.86), mask=shadow.filter(ImageFilter.GaussianBlur(S / 60)))
    d = ImageDraw.Draw(im)
    line = outline(base)
    if extra == "handle":
        d.arc([S * .32, S * .18, S * .68, S * .62], 180, 360, fill=line, width=int(S * .025))
    if extra == "loop":
        d.arc([S * .40, S * .12, S * .60, S * .32], 180, 360, fill=line, width=int(S * .02))
    for poly, part in shapes:
        fill = base if part == "body" else shade(base, 0.82) if part == "dark" else shade(base, 1.18) if sum(base) < 600 else shade(base, 0.92)
        d.polygon(poly, fill=fill, outline=line, width=int(S * .006))
    if multi:   # stripes for a multi-colour item
        mask = Image.new("L", (S, S), 0)
        md = ImageDraw.Draw(mask)
        for poly, part in shapes:
            if part == "body":
                md.polygon(poly, fill=255)
        stripes = Image.new("RGB", (S, S))
        sd = ImageDraw.Draw(stripes)
        cols = [(196, 40, 46), (240, 200, 60), (44, 92, 176), (56, 140, 80), (246, 246, 242)]
        for i, y in enumerate(range(0, S, S // 20)):
            sd.rectangle([0, y, S, y + S // 20], fill=cols[i % len(cols)])
        im.paste(stripes, mask=mask)
    # soft light from the top left
    light = Image.new("L", (S, S), 0)
    ImageDraw.Draw(light).ellipse([-S * .3, -S * .3, S * .7, S * .6], fill=36)
    im = Image.composite(Image.new("RGB", (S, S), (255, 255, 255)), im, light.filter(ImageFilter.GaussianBlur(S / 8)))
    im = im.resize((1000, 1000), Image.LANCZOS)
    b = io.BytesIO()
    im.save(b, "JPEG", quality=86, optimize=True)
    return b.getvalue()
