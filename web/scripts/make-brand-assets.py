"""Builds the web brand assets from the master logo (run: python3 scripts/make-brand-assets.py <logo.jpeg>)."""
import sys
from PIL import Image, ImageDraw, ImageFont
import numpy as np

src = Image.open(sys.argv[1]).convert("RGB")
a = np.asarray(src).astype(float)
from scipy import ndimage
# background = large, low-saturation, light regions (the gradient corners and the cross centre)
light = (a.min(axis=2) > 200) & ((a.max(axis=2) - a.min(axis=2)) < 28)
lab, n = ndimage.label(light)
sizes = ndimage.sum(light, lab, range(1, n + 1))
bg = np.isin(lab, [i + 1 for i, sz in enumerate(sizes) if sz > 1500])
bg = ndimage.binary_dilation(bg, iterations=1)
alpha = ndimage.gaussian_filter((~bg).astype(float), 0.8)
alpha = np.clip((alpha - 0.15) / 0.7, 0, 1)
rgba = np.dstack([a, alpha * 255]).astype("uint8")
img = Image.fromarray(rgba)
bbox = img.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()
img = img.crop(bbox)
side = max(img.size)
sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
sq.paste(img, ((side - img.width) // 2, (side - img.height) // 2))

def fit(size, pad=0.0, bg=None):
    pad_px = int(size * pad)
    inner = sq.resize((size - 2 * pad_px, size - 2 * pad_px), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), bg or (0, 0, 0, 0))
    out.alpha_composite(inner, (pad_px, pad_px))
    return out

fit(512).save("public/brand/logo.png")
fit(192, 0.06).save("public/brand/icon-192.png")
fit(512, 0.06).save("public/brand/icon-512.png")
fit(512, 0.2, (255, 255, 255, 255)).save("public/brand/icon-maskable-512.png")
fit(512, 0.06).save("app/icon.png")
fit(180, 0.1, (255, 255, 255, 255)).convert("RGB").save("app/apple-icon.png")
fit(64, 0.04).save("app/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
og = Image.new("RGB", (1200, 630), (248, 250, 252))
og.paste(fit(460), (110, 85), fit(460))
og.save("public/brand/og.png")
print("ok", img.size)
