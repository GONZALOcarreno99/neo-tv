"""Genera el ícono de NEO Remote (cruceta cian sobre fondo oscuro) en las densidades de Android.
Uso: python make_icon.py <carpeta_res>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

S = 512
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# fondo redondeado con degradado
bg = Image.new('RGBA', (S, S))
for y in range(S):
    t = y / S
    c = (int(24 - 14 * t), int(30 - 20 * t), int(44 - 30 * t), 255)
    ImageDraw.Draw(bg).line([(0, y), (S, y)], fill=c)
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle((16, 16, S - 16, S - 16), radius=112, fill=255)
img.paste(bg, (0, 0), mask)

# brillo cian detrás del anillo
glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((96, 96, S - 96, S - 96), fill=(34, 211, 238, 120))
glow = glow.filter(ImageFilter.GaussianBlur(40))
img = Image.alpha_composite(img, Image.composite(glow, Image.new('RGBA', (S, S)), mask))
d = ImageDraw.Draw(img)

# anillo de la cruceta y botón central
cx = cy = S // 2
d.ellipse((cx - 170, cy - 170, cx + 170, cy + 170), fill=(28, 33, 46, 255), outline=(255, 255, 255, 40), width=4)
for dx, dy in ((0, -1), (1, 0), (0, 1), (-1, 0)):
    ax, ay = cx + dx * 118, cy + dy * 118
    # chevron que apunta hacia afuera
    if dy:
        pts = [(ax - 26, ay - 14 * dy), (ax, ay + 16 * dy), (ax + 26, ay - 14 * dy)]
    else:
        pts = [(ax - 14 * dx, ay - 26), (ax + 16 * dx, ay), (ax - 14 * dx, ay + 26)]
    d.line(pts, fill=(255, 255, 255, 230), width=16, joint='curve')
d.ellipse((cx - 64, cy - 64, cx + 64, cy + 64), fill=(34, 211, 238, 255))

out = sys.argv[1]
for name, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    folder = os.path.join(out, 'mipmap-' + name)
    os.makedirs(folder, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(folder, 'ic_launcher.png'))
img.resize((256, 256), Image.LANCZOS).save(os.path.join(out, '..', 'icon_preview.png'))
print('ok')
