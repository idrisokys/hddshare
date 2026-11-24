#!/usr/bin/env python3
"""Kopya uygulama ikonu üretici. Çıktı: Kopya.icns + icon-1024.png (Pillow + numpy gerekir)."""
import struct, io, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 2          # süper-örnekleme
W = 1024 * S

def sc(v): return int(round(v * S))

def lerp(a, b, t): return a + (b - a) * t

def squircle_mask(size, x0, y0, x1, y1, n=5.0):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    a, b = (x1 - x0) / 2, (y1 - y0) / 2
    d = np.abs((xs - cx) / a) ** n + np.abs((ys - cy) / b) ** n
    # kenarı yumuşat (anti-alias)
    edge = (1.0 - d) * a / 2.2
    m = np.clip(edge * 1.0 + 0.5, 0, 1)
    return Image.fromarray((m * 255).astype(np.uint8), "L")

def gradient(size, c1, c2, angle_xy=(0.55, 1.0)):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    ax, ay = angle_xy
    t = (xs * ax + ys * ay) / (size * (ax + ay))
    t = np.clip(t, 0, 1)[..., None]
    c1 = np.array(c1, np.float32); c2 = np.array(c2, np.float32)
    arr = c1 + (c2 - c1) * t
    return Image.fromarray(arr.astype(np.uint8), "RGB").convert("RGBA")

def doc_polygon(x, y, w, h, fold):
    # sağ üst köşesi kırık sayfa
    return [(x, y), (x + w - fold, y), (x + w, y + fold), (x + w, y + h), (x, y + h)]

def rr_poly(pts, r):
    """Köşeleri yuvarlatılmış çokgen (basit: maske ile)."""
    return pts

def rounded_doc(layer_size, x, y, w, h, fold, r, fill):
    """Yuvarlak köşeli, sağ üstü kıvrık belge; ayrı bir maske olarak döner."""
    m = Image.new("L", (layer_size, layer_size), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([x, y, x + w, y + h], radius=r, fill=255)
    # sağ üst köşeyi kes
    cut = Image.new("L", (layer_size, layer_size), 0)
    cd = ImageDraw.Draw(cut)
    cd.polygon([(x + w - fold, y - 2), (x + w + 2, y - 2), (x + w + 2, y + fold)], fill=255)
    m = Image.composite(Image.new("L", m.size, 0), m, cut)
    return m

def build():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    # --- gövde (macOS ızgarası: 824/1024) ---
    x0, y0, x1, y1 = sc(100), sc(100), sc(924), sc(924)
    body_mask = squircle_mask(W, x0, y0, x1, y1)

    # gölge
    sh = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    sh.putalpha(body_mask.point(lambda v: int(v * 0.45)))
    sh = sh.filter(ImageFilter.GaussianBlur(sc(14)))
    sh_off = Image.new("RGBA", (W, W), (0, 0, 0, 0)); sh_off.paste(sh, (0, sc(12)))
    img = Image.alpha_composite(img, sh_off)

    bg = gradient(W, (29, 78, 216), (34, 211, 238), (0.45, 1.0))
    body = Image.new("RGBA", (W, W), (0, 0, 0, 0)); body.paste(bg, (0, 0), body_mask)

    # üst parlama
    gl = Image.new("RGBA", (W, W), (255, 255, 255, 0))
    ys = np.mgrid[0:W, 0:W][0].astype(np.float32)
    a = np.clip(1 - (ys - y0) / (W * 0.42), 0, 1) ** 1.6 * 70
    gl.putalpha(Image.fromarray(a.astype(np.uint8), "L"))
    gl_m = Image.new("RGBA", (W, W), (0, 0, 0, 0)); gl_m.paste(gl, (0, 0), body_mask)
    body = Image.alpha_composite(body, gl_m)

    # --- arka belge ---
    bx, by, bw, bh, fold = sc(232), sc(238), sc(340), sc(430), sc(84)
    back_m = rounded_doc(W, bx, by, bw, bh, fold, sc(34), None)
    back = Image.new("RGBA", (W, W), (255, 255, 255, 0))
    back.paste(Image.new("RGBA", (W, W), (255, 255, 255, 150)), (0, 0), back_m)
    body = Image.alpha_composite(body, back)

    # arka belge çizgileri
    ln = Image.new("RGBA", (W, W), (0, 0, 0, 0)); ld = ImageDraw.Draw(ln)
    for yy, ww in ((410, 150), (468, 150), (526, 110)):
        ld.rounded_rectangle([sc(282), sc(yy), sc(282 + ww), sc(yy + 22)], radius=sc(11), fill=(255, 255, 255, 190))
    body = Image.alpha_composite(body, ln)

    # --- ön belge (gölgeli) ---
    fx, fy, fw, fh, ffold = sc(452), sc(356), sc(340), sc(430), sc(84)
    front_m = rounded_doc(W, fx, fy, fw, fh, ffold, sc(34), None)
    fsh = Image.new("RGBA", (W, W), (10, 30, 100, 0))
    fsh.putalpha(front_m.point(lambda v: int(v * 0.40)))
    fsh = fsh.filter(ImageFilter.GaussianBlur(sc(18)))
    fsh2 = Image.new("RGBA", (W, W), (0, 0, 0, 0)); fsh2.paste(fsh, (sc(-6), sc(14)))
    body = Image.alpha_composite(body, fsh2)

    front = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    front.paste(Image.new("RGBA", (W, W), (255, 255, 255, 255)), (0, 0), front_m)
    body = Image.alpha_composite(body, front)

    # kıvrık köşe
    fd = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(fd)
    p = [(fx + fw - ffold, fy), (fx + fw, fy + ffold), (fx + fw - ffold, fy + ffold)]
    d.polygon(p, fill=(186, 214, 250, 255))
    body = Image.alpha_composite(body, fd)

    # --- hızlı kopya okları (>>) ---
    ch = Image.new("RGBA", (W, W), (0, 0, 0, 0)); cd = ImageDraw.Draw(ch)
    col1 = (29, 78, 216, 255); col2 = (34, 170, 230, 255)
    sw = sc(46)
    def chevron(cx, cy, hw, hh, color):
        pts = [(cx - hw, cy - hh), (cx + hw, cy), (cx - hw, cy + hh)]
        cd.line(pts, fill=color, width=sw, joint="curve")
        for (px, py) in (pts[0], pts[2]):
            cd.ellipse([px - sw / 2, py - sw / 2, px + sw / 2, py + sw / 2], fill=color)
        px, py = pts[1]
        cd.ellipse([px - sw / 2, py - sw / 2, px + sw / 2, py + sw / 2], fill=color)
    mid_y = sc(620)
    chevron(sc(570), mid_y, sc(46), sc(82), col2)
    chevron(sc(672), mid_y, sc(46), sc(82), col1)
    body = Image.alpha_composite(body, ch)

    # kenar ince ışık
    rim = Image.new("RGBA", (W, W), (255, 255, 255, 0))
    inner = squircle_mask(W, x0 + sc(3), y0 + sc(3), x1 - sc(3), y1 - sc(3))
    ring = Image.fromarray(np.clip(np.asarray(body_mask, np.int16) - np.asarray(inner, np.int16), 0, 255).astype(np.uint8), "L")
    rim.putalpha(ring.point(lambda v: int(v * 0.35)))
    body = Image.alpha_composite(body, rim)

    img = Image.alpha_composite(img, body)
    return img.resize((1024, 1024), Image.LANCZOS)

def make_icns(master, path):
    types = [("icp4", 16), ("icp5", 32), ("ic11", 32), ("ic12", 64), ("ic07", 128),
             ("ic13", 256), ("ic08", 256), ("ic14", 512), ("ic09", 512), ("ic10", 1024)]
    chunks = b""
    for t, px in types:
        buf = io.BytesIO()
        master.resize((px, px), Image.LANCZOS).save(buf, "PNG", optimize=True)
        data = buf.getvalue()
        chunks += t.encode() + struct.pack(">I", len(data) + 8) + data
    with open(path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    m = build()
    m.save(f"{out}/icon-1024.png")
    make_icns(m, f"{out}/Kopya.icns")
    print("ok")
