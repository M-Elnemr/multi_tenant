"""Clean flat product pictures for the electronics demo shop: one drawing per kind of device, in the product's colour (no photos, no brands, no logos)."""
import io
from PIL import Image, ImageDraw, ImageFilter

from garments import RGB

S = 2000   # drawn at twice the size, then reduced for smooth edges
NEUTRAL = (60, 64, 72)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def edge(c):
    return shade(c, 0.6) if sum(c) > 200 else shade(c, 1.8) if sum(c) < 130 else shade(c, 0.72)


def box(x0, y0, x1, y1):
    return [x0 * S / 100, y0 * S / 100, x1 * S / 100, y1 * S / 100]


def rr(d, b, r, fill, outline=None, width=0):
    d.rounded_rectangle(box(*b), radius=r * S / 100, fill=fill, outline=outline, width=int(width * S / 1000))


def screen(d, b, r=1.5):
    x0, y0, x1, y1 = b
    rr(d, b, r, (24, 30, 44))
    # a soft gradient line so the screen looks lit
    for i in range(12):
        t = i / 12
        d.line([((x0 + 3 + t * (x1 - x0 - 6)) * S / 100, (y0 + 3) * S / 100), ((x0 + 3) * S / 100, (y0 + 3 + t * (y1 - y0 - 6)) * S / 100)], fill=(46 + i * 5, 70 + i * 6, 120 + i * 6), width=int(S / 120))


def phone(d, c, wide=False):
    b = (22, 10, 78, 90) if wide else (32, 8, 68, 92)
    rr(d, b, 6, c, edge(c), 10)
    screen(d, (b[0] + 2.5, b[1] + 3, b[2] - 2.5, b[3] - 3), 4)
    rr(d, ((b[0] + b[2]) / 2 - 4, b[1] + 4, (b[0] + b[2]) / 2 + 4, b[1] + 5.4), 1, (12, 12, 16))


def tablet(d, c):
    rr(d, (14, 18, 86, 82), 4, c, edge(c), 10)
    screen(d, (17.5, 21.5, 82.5, 78.5), 2)


def laptop(d, c):
    rr(d, (18, 18, 82, 62), 2.5, c, edge(c), 10)
    screen(d, (21, 21, 79, 59), 1)
    d.polygon([(10 * S / 100, 70 * S / 100), (90 * S / 100, 70 * S / 100), (82 * S / 100, 62 * S / 100), (18 * S / 100, 62 * S / 100)], fill=shade(c, 0.92), outline=edge(c))
    rr(d, (8, 70, 92, 74), 1, shade(c, 0.8))
    rr(d, (42, 64.5, 58, 67.5), 0.6, shade(c, 0.75))


def tv(d, c):
    c = NEUTRAL if c is None else c
    rr(d, (8, 16, 92, 70), 1.2, (20, 20, 24), (60, 60, 66), 8)
    screen(d, (10, 18, 90, 68), 0.5)
    d.polygon([(30 * S / 100, 84 * S / 100), (70 * S / 100, 84 * S / 100), (56 * S / 100, 70 * S / 100), (44 * S / 100, 70 * S / 100)], fill=(54, 54, 60))


def earbuds(d, c):
    rr(d, (28, 46, 72, 82), 14, c, edge(c), 10)
    d.line([(28 * S / 100, 58 * S / 100), (72 * S / 100, 58 * S / 100)], fill=edge(c), width=int(S / 160))
    for x in (34, 58):
        d.ellipse(box(x, 16, x + 12, 30), fill=c, outline=edge(c), width=int(S / 120))
        rr(d, (x + 3.5, 26, x + 8.5, 44), 2.5, c, edge(c), 8)


def headphones(d, c):
    d.arc(box(20, 12, 80, 72), 180, 360, fill=c, width=int(S / 18))
    d.arc(box(20, 12, 80, 72), 180, 360, fill=edge(c), width=int(S / 120))
    for x in (14, 66):
        rr(d, (x, 40, x + 20, 76), 8, c, edge(c), 10)
        rr(d, (x + 4, 46, x + 16, 70), 6, shade(c, 0.8))


def speaker(d, c, bar=False):
    if bar:
        rr(d, (6, 40, 94, 60), 8, c, edge(c), 10)
        for x in range(14, 90, 8):
            d.ellipse(box(x, 47, x + 4, 53), fill=shade(c, 0.6))
        return
    rr(d, (18, 30, 82, 74), 18, c, edge(c), 10)
    for y in range(38, 68, 6):
        d.line([(26 * S / 100, y * S / 100), (74 * S / 100, y * S / 100)], fill=shade(c, 0.75), width=int(S / 200))
    rr(d, (40, 26, 60, 31), 2, shade(c, 0.7))


def watch(d, c, band=False):
    strap = shade(c, 0.9)
    rr(d, (38, 4, 62, 96), 6, strap, edge(c), 6)
    if band:
        rr(d, (36, 34, 64, 66), 12, (24, 24, 30), edge(c), 8)
        screen(d, (39, 38, 61, 62), 8)
        return
    rr(d, (30, 28, 70, 72), 9, (30, 30, 36), edge(c), 10)
    screen(d, (34, 32, 66, 68), 6)
    rr(d, (70, 44, 74, 54), 1.5, (90, 90, 96))


def charger(d, c, car=False, wireless=False):
    if wireless:
        d.ellipse(box(18, 34, 82, 74), fill=c, outline=edge(c), width=int(S / 100))
        d.ellipse(box(36, 46, 64, 62), outline=shade(c, 0.7), width=int(S / 160))
        return
    if car:
        rr(d, (40, 14, 60, 80), 6, c, edge(c), 10)
        rr(d, (44, 80, 56, 90), 2, (170, 170, 176))
        return
    rr(d, (28, 30, 72, 78), 8, c, edge(c), 10)
    for x in (40, 55):
        rr(d, (x, 14, x + 5, 30), 1, (180, 180, 186))
    rr(d, (44, 60, 56, 64), 1, shade(c, 0.55))


def cable(d, c):
    for b, a0, a1 in ((box(24, 22, 76, 74), 20, 340), (box(32, 30, 68, 66), 30, 330)):
        d.arc(b, a0, a1, fill=edge(c), width=int(S / 30))   # an outline, so a white cable shows on the light background
        d.arc(b, a0, a1, fill=c, width=int(S / 48))
    rr(d, (64, 10, 74, 26), 2, shade(c, 0.85), edge(c), 6)
    rr(d, (66, 4, 72, 10), 0.8, (180, 180, 186))
    rr(d, (26, 74, 36, 90), 2, shade(c, 0.85), edge(c), 6)
    rr(d, (28, 90, 34, 96), 0.8, (180, 180, 186))


def powerbank(d, c, mag=False):
    if mag:
        rr(d, (26, 18, 74, 82), 10, c, edge(c), 10)
        d.ellipse(box(36, 32, 64, 60), outline=shade(c, 0.7), width=int(S / 120))
        return
    rr(d, (22, 12, 78, 88), 7, c, edge(c), 10)
    for i in range(4):
        d.ellipse(box(42 + i * 5, 76, 45 + i * 5, 79), fill=(80, 200, 120) if i < 3 else (90, 90, 96))


def case(d, c):
    rr(d, (30, 6, 70, 94), 8, c, edge(c), 14)
    rr(d, (35, 11, 65, 89), 5, (232, 232, 236))
    rr(d, (35, 11, 50, 30), 4, c)
    for (x, y) in ((39, 15), (39, 22)):
        d.ellipse(box(x, y, x + 6, y + 6), fill=(30, 30, 36))


def glass(d, c=None):
    rr(d, (30, 8, 70, 92), 6, (210, 230, 240), (150, 180, 200), 8)
    d.line([(36 * S / 100, 20 * S / 100), (56 * S / 100, 60 * S / 100)], fill=(255, 255, 255), width=int(S / 60))
    d.line([(44 * S / 100, 18 * S / 100), (62 * S / 100, 52 * S / 100)], fill=(245, 250, 255), width=int(S / 120))


def mouse(d, c):
    d.ellipse(box(32, 14, 68, 86), fill=c, outline=edge(c), width=int(S / 100))
    d.line([(50 * S / 100, 14 * S / 100), (50 * S / 100, 42 * S / 100)], fill=edge(c), width=int(S / 160))
    rr(d, (48, 24, 52, 34), 2, shade(c, 0.6))


def keyboard(d, c):
    rr(d, (6, 32, 94, 70), 3, c, edge(c), 10)
    for row in range(4):
        for k in range(12):
            rr(d, (9 + k * 7, 35 + row * 8.5, 14.5 + k * 7, 41.5 + row * 8.5), 0.8, shade(c, 0.78) if sum(c) > 200 else shade(c, 1.5))


def small_box(d, c, ports=3):
    rr(d, (18, 38, 82, 62), 5, c, edge(c), 10)
    for i in range(ports):
        rr(d, (26 + i * 14, 47, 34 + i * 14, 53), 1, (40, 40, 46))


def flash(d, c):
    rr(d, (40, 20, 60, 72), 4, c, edge(c), 10)
    rr(d, (43, 72, 57, 86), 1, (190, 190, 196))


def sdcard(d, c=None):
    c = (40, 44, 52) if c is None else c
    d.polygon([(34 * S / 100, 16 * S / 100), (60 * S / 100, 16 * S / 100), (66 * S / 100, 24 * S / 100), (66 * S / 100, 84 * S / 100), (34 * S / 100, 84 * S / 100)], fill=c, outline=edge(c))
    for x in range(38, 62, 5):
        rr(d, (x, 20, x + 3, 30), 0.5, (210, 180, 90))


def router(d, c):
    rr(d, (16, 50, 84, 72), 6, c, edge(c), 10)
    for x in (24, 40, 60, 76):
        rr(d, (x - 2, 18, x + 2, 50), 2, shade(c, 0.85))
    for i in range(5):
        d.ellipse(box(30 + i * 9, 59, 33 + i * 9, 62), fill=(80, 200, 120))


def controller(d, c):
    d.rounded_rectangle(box(14, 34, 86, 66), radius=S * 0.12, fill=c, outline=edge(c), width=int(S / 100))
    for (x, y) in ((24, 48), (64, 56)):
        d.ellipse(box(x, y - 6, x + 12, y + 6), fill=shade(c, 0.7))
    for (x, y) in ((70, 40), (76, 46), (64, 46), (70, 52)):
        d.ellipse(box(x, y, x + 5, y + 5), fill=shade(c, 0.6))


def bag(d, c, sleeve=False):
    if sleeve:
        rr(d, (12, 26, 88, 78), 4, c, edge(c), 10)
        d.line([(16 * S / 100, 34 * S / 100), (84 * S / 100, 34 * S / 100)], fill=edge(c), width=int(S / 200))
        return
    rr(d, (24, 18, 76, 88), 12, c, edge(c), 10)
    rr(d, (32, 50, 68, 80), 6, shade(c, 0.85))
    d.arc(box(36, 6, 64, 30), 180, 360, fill=shade(c, 0.75), width=int(S / 60))


def holder(d, c):
    rr(d, (42, 40, 58, 86), 3, (70, 70, 76))
    rr(d, (28, 10, 72, 48), 4, c, edge(c), 10)


DRAW = {
    "phones": phone, "tablets": tablet, "laptops": laptop, "tvs": tv, "phone-earphones": earbuds, "headphones": headphones, "speakers-audio": speaker,
    "smart-watches": watch, "chargers-cables": charger, "power-banks": powerbank, "phone-cases": case, "screen-protectors": glass, "computer-accessories": small_box,
    "gaming-accessories": controller, "storage-memory": flash, "networking": router, "laptop-bags": bag, "phone-holders": holder,
}


def picture(key, category, colour):
    """A 1000 x 1000 JPEG of the product in that colour (a soft studio background, the device in the middle)."""
    c = RGB.get(colour) or NEUTRAL
    im = Image.new("RGB", (S, S), (244, 245, 248))
    d = ImageDraw.Draw(im)
    # soft floor shadow
    sh = Image.new("L", (S, S), 0)
    ImageDraw.Draw(sh).ellipse(box(22, 86, 78, 94), fill=70)
    im.paste((200, 202, 208), mask=sh.filter(ImageFilter.GaussianBlur(S / 60)))
    k = key
    if k.startswith("ch-car"): charger(d, c, car=True)
    elif k.startswith("ch-wireless"): charger(d, c, wireless=True)
    elif k.startswith("cb-") or k == "ch-bundle": cable(d, c)
    elif k.startswith("pb-mag"): powerbank(d, c, mag=True)
    elif k.startswith("sp-bar"): speaker(d, c, bar=True)
    elif k.startswith("sw-band"): watch(d, c, band=True)
    elif k.startswith("au-max") or k.startswith("au-kids") or k.startswith("au-gaming"): headphones(d, c)
    elif k.startswith("au-neck") or k.startswith("au-wired"): earbuds(d, c)
    elif k == "ac-mouse" or k == "ac-gmouse": mouse(d, c)
    elif k in ("ac-kb", "ac-mech"): keyboard(d, c)
    elif k == "ac-pad": rr(d, (8, 36, 92, 66), 3, c, edge(c), 8)
    elif k == "ac-stand": holder(d, c)
    elif k == "ac-cam": small_box(d, c, ports=1)
    elif k == "st-sd": sdcard(d, None)
    elif k == "st-ssd": small_box(d, c, ports=1)
    elif k == "nw-ext": small_box(d, c, ports=2)
    elif k == "bg-sleeve": bag(d, c, sleeve=True)
    elif k == "ph-fold": phone(d, c, wide=True)
    else: DRAW.get(category, small_box)(d, c)
    out = io.BytesIO()
    im.resize((1000, 1000), Image.LANCZOS).save(out, "JPEG", quality=88)
    return out.getvalue()


if __name__ == "__main__":   # a contact sheet to look at every drawing: python3 devices.py /tmp/devices.jpg
    import sys
    import business_shops_data as D
    items = D.ELECTRONICS
    cols = 10
    sheet = Image.new("RGB", (cols * 200, ((len(items) + cols - 1) // cols) * 200), (255, 255, 255))
    for i, it in enumerate(items):
        pic = Image.open(io.BytesIO(picture(it[0], it[2], (it[4] or [None])[0]))).resize((200, 200))
        sheet.paste(pic, ((i % cols) * 200, (i // cols) * 200))
    sheet.save(sys.argv[1] if len(sys.argv) > 1 else "devices.jpg", quality=85)
