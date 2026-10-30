"""Genera los 6 fondos de NEO (uno por sección) como JPEG 16:9.
Uso: python make_backgrounds.py <carpeta_salida>
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1536, 864
YY, XX = np.mgrid[0:H, 0:W].astype(np.float32)


def hexc(h):
    h = h.lstrip('#')
    return np.array([int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)], dtype=np.float32)


def base(top, bottom):
    t = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
    col = hexc(top) * (1 - t) + hexc(bottom) * t
    return np.broadcast_to(col, (H, W, 3)).copy()


def blob(a, cx, cy, rx, ry, color, amp):
    d = ((XX - cx) / rx) ** 2 + ((YY - cy) / ry) ** 2
    a += (amp * np.exp(-d))[..., None] * np.array(color, dtype=np.float32)


def mask(fn, blur=0):
    im = Image.new('L', (W, H), 0)
    fn(ImageDraw.Draw(im))
    if blur:
        im = im.filter(ImageFilter.GaussianBlur(blur))
    return np.asarray(im, dtype=np.float32) / 255


def add(a, m, color, amp):
    a += (amp * m)[..., None] * np.array(color, dtype=np.float32)


def glowing(a, fn, color, amp, blur=6):
    add(a, mask(fn), color, amp)
    add(a, mask(fn, blur), color, amp * 1.7)


def stars(a, n, seed, color=(1, 1, 1)):
    def f(d):
        rng = random.Random(seed)
        for _ in range(n):
            x, y = rng.randrange(W), rng.randrange(int(H * 0.85))
            r = rng.choice([1, 1, 1, 2])
            d.ellipse((x - r, y - r, x + r, y + r), fill=rng.randint(70, 255))
    add(a, mask(f, 0.7), color, 0.55)


def finish(a, name, out):
    r = np.sqrt(((XX - W / 2) / (W * 0.62)) ** 2 + ((YY - H / 2) / (H * 0.7)) ** 2)
    a *= (1 - 0.5 * np.clip(r, 0, 1.2) ** 2)[..., None]
    a *= (1 - 0.4 * np.clip((YY / H - 0.72) / 0.28, 0, 1))[..., None]
    a = 1 - np.exp(-a * 1.35)
    a += np.random.default_rng(3).normal(0, 0.009, a.shape).astype(np.float32)
    img = Image.fromarray((np.clip(a, 0, 1) * 255).astype('uint8'))
    path = os.path.join(out, name)
    img.save(path, quality=84, optimize=True)
    print(name, os.path.getsize(path) // 1024, 'KB')


# ---------- 0 NEXUS: portal de anillos ----------
def nexus():
    a = base('#050A1A', '#03050C')
    cx, cy = 1080, 330
    blob(a, 1050, 300, 520, 360, (0.0, 0.75, 1.0), 0.38)
    blob(a, 150, 760, 560, 300, (0.25, 0.3, 1.0), 0.28)

    def rings(d):
        for i, r in enumerate([70, 125, 190, 265, 350, 445, 550]):
            d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=int(255 * (0.95 - 0.1 * i)), width=2)
    glowing(a, rings, (0.2, 0.85, 1.0), 0.35, 5)

    def rays(d):
        rng = random.Random(4)
        for k in range(96):
            ang = k * math.tau / 96
            r0 = rng.uniform(90, 160)
            r1 = r0 + rng.uniform(80, 520)
            d.line((cx + r0 * math.cos(ang), cy + r0 * math.sin(ang),
                    cx + r1 * math.cos(ang), cy + r1 * math.sin(ang)), fill=rng.randint(80, 255), width=1)
    glowing(a, rays, (0.4, 0.9, 1.0), 0.25, 3)
    blob(a, cx, cy, 210, 210, (0.1, 0.7, 1.0), 0.5)
    blob(a, cx, cy, 80, 80, (0.8, 1.0, 1.0), 1.0)
    stars(a, 260, 1)
    return a


# ---------- 1 MÓDULOS: suelo de rejilla ----------
def modulos():
    a = base('#090620', '#04030C')
    hy, vx = 430, 768
    blob(a, vx, hy, 900, 90, (0.7, 0.3, 1.0), 0.65)
    blob(a, vx, hy - 40, 300, 160, (1.0, 0.4, 0.8), 0.35)

    def grid(d):
        for k in range(-24, 25):
            d.line((vx + k * 6, hy, vx + k * 150, H), fill=200, width=2)
        for k in range(1, 14):
            y = hy + (H - hy) * ((k / 13) ** 2.2)
            d.line((0, y, W, y), fill=int(60 + 190 * (k / 13)), width=2)
    glowing(a, grid, (0.65, 0.35, 1.0), 0.35, 4)

    def hexes(d):
        rng = random.Random(9)
        for _ in range(26):
            x, y, r = rng.uniform(0, W), rng.uniform(40, hy - 60), rng.uniform(26, 90)
            pts = [(x + r * math.cos(math.pi / 3 * i + math.pi / 6),
                    y + r * math.sin(math.pi / 3 * i + math.pi / 6)) for i in range(6)]
            d.line(pts + [pts[0]], fill=rng.randint(60, 200), width=2)
    glowing(a, hexes, (0.5, 0.55, 1.0), 0.28, 4)
    stars(a, 160, 2)
    return a


# ---------- 2 SELECCIÓN PRIME: haces dorados y bokeh ----------
def prime():
    a = base('#0C0804', '#050302')
    blob(a, 1100, 110, 560, 320, (1.0, 0.62, 0.15), 0.42)
    blob(a, 200, 780, 520, 260, (0.9, 0.2, 0.1), 0.22)

    def beams(d):
        for x0, w in [(900, 70), (1100, 120), (1300, 60), (1500, 150)]:
            d.polygon([(x0, 0), (x0 + w, 0), (x0 + w - 640, H), (x0 - w - 640, H)], fill=255)
    add(a, mask(beams, 30), (1.0, 0.7, 0.3), 0.24)

    def bokeh(d):
        rng = random.Random(5)
        for _ in range(46):
            r = rng.uniform(8, 46)
            x, y = rng.uniform(0, W), rng.uniform(0, H)
            d.ellipse((x - r, y - r, x + r, y + r), fill=rng.randint(40, 200))
    add(a, mask(bokeh, 2.2), (1.0, 0.78, 0.4), 0.35)
    stars(a, 120, 3, (1.0, 0.85, 0.6))
    return a


# ---------- 3 ESCÁNER: radar ----------
def escaner():
    a = base('#02110F', '#020706')
    cx, cy = 1040, 400
    blob(a, cx, cy, 560, 480, (0.0, 0.9, 0.6), 0.30)

    def circles(d):
        for r in range(70, 520, 75):
            d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=200, width=2)
        d.line((cx - 560, cy, cx + 560, cy), fill=140, width=1)
        d.line((cx, cy - 460, cx, cy + 460), fill=140, width=1)
        for k in range(0, 360, 3):
            ang = math.radians(k)
            r1 = 500 + (18 if k % 15 == 0 else 8)
            d.line((cx + 500 * math.cos(ang), cy + 500 * math.sin(ang),
                    cx + r1 * math.cos(ang), cy + r1 * math.sin(ang)), fill=220, width=1)
    glowing(a, circles, (0.1, 1.0, 0.65), 0.3, 4)

    ang = np.arctan2(YY - cy, XX - cx)
    rr = np.hypot(XX - cx, YY - cy)
    diff = (math.radians(-35) - ang) % math.tau
    wedge = np.where(diff < math.radians(75), np.exp(-diff / 0.45), 0) * (rr < 500)
    a += (0.55 * wedge)[..., None] * np.array((0.1, 1.0, 0.6), dtype=np.float32)

    def blips(d):
        rng = random.Random(11)
        for _ in range(9):
            t, r = rng.uniform(0, math.tau), rng.uniform(60, 470)
            x, y = cx + r * math.cos(t), cy + r * math.sin(t)
            d.ellipse((x - 5, y - 5, x + 5, y + 5), fill=255)
    glowing(a, blips, (0.5, 1.0, 0.8), 0.7, 7)
    stars(a, 90, 6, (0.7, 1, 0.9))
    return a


# ---------- 4 IDENTIDAD: orbe y ondas ----------
def identidad():
    a = base('#0E0518', '#05020B')
    cx, cy = 1060, 300
    blob(a, cx, cy, 420, 360, (0.9, 0.2, 0.8), 0.35)
    blob(a, 260, 700, 520, 300, (0.3, 0.25, 1.0), 0.28)
    blob(a, cx, cy, 150, 150, (1.0, 0.5, 0.95), 0.9)
    blob(a, cx, cy, 70, 70, (1.0, 0.9, 1.0), 0.8)

    def halo(d):
        for r in (190, 250, 330):
            d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=170, width=2)
    glowing(a, halo, (1.0, 0.5, 0.95), 0.25, 5)

    pink, cyan = np.array((1.0, 0.35, 0.8)), np.array((0.2, 0.85, 1.0))
    for i in range(16):
        t = i / 15

        def wave(d, i=i):
            y0, amp = 580 + i * 7, 26 + i * 3
            pts = [(x, y0 + amp * math.sin(x / (180 + i * 9) + i * 0.5) + 16 * math.sin(x / 70 + i))
                   for x in range(0, W + 6, 6)]
            d.line(pts, fill=int(110 + 8 * i), width=2)
        glowing(a, wave, pink * (1 - t) + cyan * t, 0.13, 4)
    stars(a, 150, 8)
    return a


# ---------- 5 SISTEMA: circuitos ----------
def sistema():
    a = base('#050C16', '#03060B')
    blob(a, 1120, 230, 560, 360, (0.1, 0.65, 1.0), 0.30)
    blob(a, 200, 700, 480, 260, (0.2, 0.4, 0.9), 0.18)

    def traces(d):
        rng = random.Random(7)
        moves = [(1, 0), (1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 0)]
        for _ in range(46):
            x, y = rng.randrange(0, W, 48), rng.randrange(48, H - 48, 48)
            for _s in range(rng.randint(3, 7)):
                mx, my = rng.choice(moves)
                n = rng.randint(1, 4) * 48
                nx = min(max(x + mx * n, 0), W)
                ny = min(max(y + my * n, 0), H)
                d.line((x, y, nx, ny), fill=rng.randint(70, 230), width=2)
                x, y = nx, ny
            d.ellipse((x - 5, y - 5, x + 5, y + 5), outline=255, width=2)
    glowing(a, traces, (0.3, 0.8, 1.0), 0.18, 4)
    stars(a, 100, 12, (0.7, 0.9, 1))
    return a


if __name__ == '__main__':
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for i, fn in enumerate([nexus, modulos, prime, escaner, identidad, sistema]):
        finish(fn(), 'bg%d.jpg' % i, out)
