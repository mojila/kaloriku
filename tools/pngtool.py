"""Minimal dependency-free PNG decode/encode with alpha-aware resizing.

Used to derive launcher icons and in-app logos from asset/burger.png.
"""
import struct
import sys
import zlib


def read_png(path):
    d = open(path, "rb").read()
    assert d[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, palette, trns = 8, b"", None, None
    w = h = ct = None
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct, comp, filt, inter = struct.unpack(">IIBBBBB", data)
            assert bd == 8, "only 8-bit supported"
            assert inter == 0, "interlace not supported"
        elif typ == b"IDAT":
            idat += data
        elif typ == b"PLTE":
            palette = data
        elif typ == b"tRNS":
            trns = data
        pos += 12 + ln

    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    stride = w * ch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line

    rgba = bytearray(w * h * 4)
    if ct == 6:
        rgba = out
    elif ct == 2:
        for i in range(w * h):
            rgba[i * 4:i * 4 + 3] = out[i * 3:i * 3 + 3]
            rgba[i * 4 + 3] = 255
    elif ct == 0:
        for i in range(w * h):
            v = out[i]
            rgba[i * 4:i * 4 + 3] = bytes((v, v, v))
            rgba[i * 4 + 3] = 255
    elif ct == 3:
        for i in range(w * h):
            idx = out[i]
            rgba[i * 4:i * 4 + 3] = palette[idx * 3:idx * 3 + 3]
            rgba[i * 4 + 3] = trns[idx] if trns and idx < len(trns) else 255
    elif ct == 4:
        for i in range(w * h):
            v, a = out[i * 2], out[i * 2 + 1]
            rgba[i * 4:i * 4 + 3] = bytes((v, v, v))
            rgba[i * 4 + 3] = a
    return w, h, rgba


def write_png(path, w, h, rgba):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)  # filter: None
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(typ, data):
        return (
            struct.pack(">I", len(data))
            + typ
            + data
            + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    open(path, "wb").write(png)


def resize(w, h, rgba, nw, nh):
    """Bilinear resize in premultiplied alpha space to avoid dark halos."""
    if (w, h) == (nw, nh):
        return bytearray(rgba)
    out = bytearray(nw * nh * 4)
    xr = w / nw
    yr = h / nh
    for y in range(nh):
        sy = (y + 0.5) * yr - 0.5
        y0 = int(sy) if sy >= 0 else 0
        y0 = min(y0, h - 1)
        y1 = min(y0 + 1, h - 1)
        fy = sy - y0 if 0 <= sy else 0.0
        fy = min(max(fy, 0.0), 1.0)
        for x in range(nw):
            sx = (x + 0.5) * xr - 0.5
            x0 = int(sx) if sx >= 0 else 0
            x0 = min(x0, w - 1)
            x1 = min(x0 + 1, w - 1)
            fx = sx - x0 if 0 <= sx else 0.0
            fx = min(max(fx, 0.0), 1.0)

            acc = [0.0, 0.0, 0.0, 0.0]
            for (px, py, wt) in (
                (x0, y0, (1 - fx) * (1 - fy)),
                (x1, y0, fx * (1 - fy)),
                (x0, y1, (1 - fx) * fy),
                (x1, y1, fx * fy),
            ):
                i = (py * w + px) * 4
                a = rgba[i + 3] / 255.0
                acc[0] += rgba[i] * a * wt
                acc[1] += rgba[i + 1] * a * wt
                acc[2] += rgba[i + 2] * a * wt
                acc[3] += a * wt
            o = (y * nw + x) * 4
            a = acc[3]
            if a > 1e-6:
                out[o] = min(255, int(round(acc[0] / a)))
                out[o + 1] = min(255, int(round(acc[1] / a)))
                out[o + 2] = min(255, int(round(acc[2] / a)))
            out[o + 3] = min(255, int(round(a * 255)))
    return out


def content_bbox(w, h, rgba, alpha_threshold=8):
    """Bounding box of pixels whose alpha exceeds the threshold."""
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        row = y * w * 4
        for x in range(w):
            if rgba[row + x * 4 + 3] > alpha_threshold:
                if x < minx:
                    minx = x
                if x > maxx:
                    maxx = x
                if y < miny:
                    miny = y
                if y > maxy:
                    maxy = y
    return minx, miny, maxx, maxy


def crop(w, h, rgba, box):
    x0, y0, x1, y1 = box
    cw, chh = x1 - x0 + 1, y1 - y0 + 1
    out = bytearray(cw * chh * 4)
    for y in range(chh):
        src = ((y + y0) * w + x0) * 4
        out[y * cw * 4:(y + 1) * cw * 4] = rgba[src:src + cw * 4]
    return cw, chh, out


def canvas(w, h, rgba, cw, chh, ox, oy):
    """Place a cropped image onto a transparent canvas at the given offset."""
    out = bytearray(cw * chh * 4)
    for y in range(h):
        ty = y + oy
        if ty < 0 or ty >= chh:
            continue
        src = y * w * 4
        dst = (ty * cw + ox) * 4
        out[dst:dst + w * 4] = rgba[src:src + w * 4]
    return out
