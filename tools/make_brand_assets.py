"""Generates launcher icons and the in-app logo from asset/burger.png.

Run from the repo root:  python3 tools/make_brand_assets.py

Outputs (per density bucket):
  phone|wear/src/main/res/mipmap-*/ic_launcher_foreground.png   adaptive foreground
  phone/src/main/res/mipmap-*/ic_launcher.png                   legacy square icon
  phone/src/main/res/mipmap-*/ic_launcher_round.png             legacy round icon
  phone|wear/src/main/res/drawable-*/ic_logo.png                in-app logo

Previews are written to tools/preview/ for visual inspection.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pngtool

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "asset", "burger.png")
PREVIEW = os.path.join(ROOT, "tools", "preview")

# Adaptive icon canvas is 108dp; the art occupies this fraction of it so the
# content survives every launcher mask (circle, squircle, rounded square).
FOREGROUND_FRACTION = 0.66

BACKGROUND = (0xFA, 0xF9, 0xF7)  # matches the light theme background

DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}


def load_art():
    w, h, px = pngtool.read_png(SOURCE)
    # Trim fully transparent padding, if any.
    box = pngtool.content_bbox(w, h, px, alpha_threshold=4)
    if box != (0, 0, w - 1, h - 1):
        w, h, px = pngtool.crop(w, h, px, box)
    return w, h, px


def art_on_canvas(art, canvas_px, art_fraction, opaque_bg=None, circle=False):
    """Places the art centred on a square canvas, optionally on an opaque disc."""
    aw, ah, apx = art
    target = max(1, int(round(canvas_px * art_fraction)))
    scaled = pngtool.resize(aw, ah, apx, target, target)

    out = bytearray(canvas_px * canvas_px * 4)
    if opaque_bg is not None:
        r, g, b = opaque_bg
        for i in range(canvas_px * canvas_px):
            out[i * 4] = r
            out[i * 4 + 1] = g
            out[i * 4 + 2] = b
            out[i * 4 + 3] = 255

    # Centred placement.
    off = (canvas_px - target) // 2
    for y in range(target):
        dst = ((off + y) * canvas_px + off) * 4
        src = y * target * 4
        row = scaled[src:src + target * 4]
        out[dst:dst + target * 4] = row

    if circle:
        cx = cy = (canvas_px - 1) / 2.0
        radius = canvas_px / 2.0
        for y in range(canvas_px):
            for x in range(canvas_px):
                dx, dy = x - cx, y - cy
                if dx * dx + dy * dy > radius * radius:
                    i = (y * canvas_px + x) * 4
                    out[i + 3] = 0
    return canvas_px, canvas_px, out


def ensure_dir(path):
    os.makedirs(path, exist_ok=True)


def write(path, w, h, px):
    ensure_dir(os.path.dirname(path))
    pngtool.write_png(path, w, h, px)


def apply_mask(w, h, px, kind):
    """Cuts the image to a launcher mask shape; outside pixels become transparent."""
    cx = (w - 1) / 2.0
    cy = (h - 1) / 2.0
    radius = w / 2.0
    out = bytearray(px)
    for y in range(h):
        for x in range(w):
            dx, dy = (x - cx) / radius, (y - cy) / radius
            inside = True
            if kind == "circle":
                inside = dx * dx + dy * dy <= 1.0
            elif kind == "squircle":
                # Superellipse approximating the Pixel launcher squircle.
                inside = (abs(dx) ** 4 + abs(dy) ** 4) <= 1.0
            elif kind == "rounded":
                # Rounded square with a ~20% corner radius.
                r = 0.4
                ax, ay = abs(dx), abs(dy)
                if ax > 1.0 - r and ay > 1.0 - r:
                    ox, oy = ax - (1.0 - r), ay - (1.0 - r)
                    inside = (ox * ox + oy * oy) <= r * r
            if not inside:
                out[(y * w + x) * 4 + 3] = 0
    return out


def composite_on(w, h, px, backdrop=(0x6E, 0x6B, 0x64)):
    """Flattens a translucent image onto a solid backdrop for previewing."""
    r, g, b = backdrop
    out = bytearray(w * h * 4)
    for i in range(w * h):
        a = px[i * 4 + 3] / 255.0
        out[i * 4] = int(round(px[i * 4] * a + r * (1 - a)))
        out[i * 4 + 1] = int(round(px[i * 4 + 1] * a + g * (1 - a)))
        out[i * 4 + 2] = int(round(px[i * 4 + 2] * a + b * (1 - a)))
        out[i * 4 + 3] = 255
    return out


def main():
    art = load_art()
    print(f"source art trimmed to {art[0]}x{art[1]}")

    # ---------------------------------------------------------------- foreground
    # The adaptive-icon XMLs reference @drawable/ic_launcher_foreground, so the
    # PNGs live in drawable-* and replace the old vector of the same name.
    for mod in ("phone", "wear"):
        for bucket, scale in DENSITIES.items():
            size = int(round(108 * scale))
            w, h, px = art_on_canvas(art, size, FOREGROUND_FRACTION)
            write(
                os.path.join(ROOT, mod, "src/main/res", f"drawable-{bucket}",
                             "ic_launcher_foreground.png"),
                w, h, px,
            )

    # ------------------------------------------------------- legacy phone icons
    # Pre-API-26 launchers need a full-bleed bitmap: art on an opaque background.
    for bucket, scale in DENSITIES.items():
        size = int(round(48 * scale))
        w, h, px = art_on_canvas(art, size, 0.80, opaque_bg=BACKGROUND)
        write(
            os.path.join(ROOT, "phone", "src/main/res", f"mipmap-{bucket}", "ic_launcher.png"),
            w, h, px,
        )
        w, h, px = art_on_canvas(art, size, 0.68, opaque_bg=BACKGROUND, circle=True)
        write(
            os.path.join(ROOT, "phone", "src/main/res", f"mipmap-{bucket}",
                         "ic_launcher_round.png"),
            w, h, px,
        )

    # ------------------------------------------------------------------ logo
    # The in-app logo is shown on the app's own surface, so it keeps its alpha and
    # fills the canvas more generously than the masked launcher icon.
    for mod in ("phone", "wear"):
        for bucket, scale in DENSITIES.items():
            size = int(round(48 * scale))
            w, h, px = art_on_canvas(art, size, 0.96)
            write(
                os.path.join(ROOT, mod, "src/main/res", f"drawable-{bucket}", "ic_logo.png"),
                w, h, px,
            )

    # --------------------------------------------------------------- previews
    # Render the icon under the launcher masks people actually see, flattened onto
    # a mid-grey backdrop so transparent cut-away areas are visible.
    ensure_dir(PREVIEW)
    size = 432
    for name, fraction, mask in (
        ("adaptive_circle", FOREGROUND_FRACTION, "circle"),
        ("adaptive_squircle", FOREGROUND_FRACTION, "squircle"),
        ("adaptive_rounded", FOREGROUND_FRACTION, "rounded"),
        ("legacy_square", 0.80, None),
        ("legacy_round", 0.68, "circle"),
    ):
        w, h, px = art_on_canvas(art, size, fraction, opaque_bg=BACKGROUND)
        if mask:
            px = apply_mask(w, h, px, mask)
        w, h, px = w, h, composite_on(w, h, px)
        write(os.path.join(PREVIEW, f"{name}.png"), w, h, px)

    # The logo keeps its alpha; preview it on the app's light and dark surfaces.
    for label, backdrop in (("light", BACKGROUND), ("dark", (0x12, 0x12, 0x11))):
        w, h, px = art_on_canvas(art, size, 0.96)
        w, h, px = w, h, composite_on(w, h, px, backdrop)
        write(os.path.join(PREVIEW, f"logo_{label}.png"), w, h, px)
    print(f"previews written to {PREVIEW}")


if __name__ == "__main__":
    main()
