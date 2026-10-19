#!/usr/bin/env python3
"""Генератор безшовних фотореалістичних текстур (albedo + normal) для «Оборони міста».

Усі текстури створюються процедурно (numpy + Pillow), тому не мають сторонніх ліцензій.
Запуск з теки oborona/:  python3 tools/gen_textures.py
"""
import os
import numpy as np
from PIL import Image, ImageDraw

N = 512
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "textures")
rng = np.random.default_rng(1991)


def fnoise(beta, n=N, aspect=(1.0, 1.0)):
    """Безшовний фрактальний шум 0..1 (1/f^beta)."""
    w = rng.standard_normal((n, n))
    F = np.fft.fft2(w)
    fy = np.fft.fftfreq(n)[:, None] * aspect[0]
    fx = np.fft.fftfreq(n)[None, :] * aspect[1]
    f = np.sqrt(fx ** 2 + fy ** 2)
    f[0, 0] = 1.0
    F /= f ** beta
    F[0, 0] = 0
    r = np.real(np.fft.ifft2(F))
    return (r - r.min()) / (r.max() - r.min())


def blur(a, radius):
    """Гаусове розмиття через FFT — періодичне, тож текстура лишається безшовною."""
    f = np.fft.fftfreq(N)
    g = np.exp(-2 * (np.pi * radius) ** 2 * (f[:, None] ** 2 + f[None, :] ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(a) * g))


def cracks(count, width=2, length=(80, 260)):
    """Маска тріщин (1 — тріщина)."""
    img = Image.new("L", (N * 3, N * 3), 0)
    d = ImageDraw.Draw(img)
    for _ in range(count):
        x, y = rng.uniform(0, N, 2)
        ang = rng.uniform(0, np.pi * 2)
        pts = [(x, y)]
        for _ in range(int(rng.uniform(*length) / 6)):
            ang += rng.normal(0, 0.45)
            x += np.cos(ang) * 6
            y += np.sin(ang) * 6
            pts.append((x, y))
        for ox in (0, N, 2 * N):
            for oy in (0, N, 2 * N):
                d.line([(px + ox, py + oy) for px, py in pts], fill=255, width=width)
    m = np.array(img, dtype=np.float32) / 255.0
    acc = np.zeros((N, N), np.float32)
    for i in range(3):
        for j in range(3):
            acc = np.maximum(acc, m[i * N:(i + 1) * N, j * N:(j + 1) * N])
    return acc


def normal_map(h, strength):
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * strength
    dy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * strength
    n = np.stack([-dx, dy, np.ones_like(h)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    return n * 0.5 + 0.5


def colorize(base, shade):
    return np.clip(np.array(base)[None, None, :] * shade[..., None], 0, 1)


def save(name, rgb, h, strength):
    os.makedirs(OUT, exist_ok=True)
    Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8)).save(
        os.path.join(OUT, f"{name}_albedo.jpg"), quality=88)
    Image.fromarray((normal_map(h, strength) * 255).astype(np.uint8)).save(
        os.path.join(OUT, f"{name}_normal.png"), optimize=True)
    print("ok", name)


def white():
    return rng.random((N, N))


# ---------- Матеріали ----------

def concrete():
    low = fnoise(1.8)
    mid = fnoise(1.1)
    pores = (white() > 0.985).astype(float)
    pores = blur(pores, 0.6)
    streak = blur(fnoise(1.2, aspect=(12.0, 1.0)), 1.0)  # вертикальні патьоки
    cr = blur(cracks(5, 1), 0.7)
    shade = 0.78 + 0.25 * (low - 0.5) + 0.12 * (mid - 0.5) + 0.05 * (white() - 0.5)
    shade -= 0.22 * np.clip(streak - 0.55, 0, 1) * 2
    shade -= 0.3 * pores + 0.22 * cr
    rgb = colorize((0.58, 0.57, 0.54), shade)
    h = 0.5 * mid + 0.2 * white() - 0.6 * pores - 0.8 * cr
    return rgb, h


def brick_pattern():
    rows, per_row, mortar = 12, 4, 5
    rh, bw = N / rows, N / per_row
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    row = np.floor(yy / rh).astype(int)
    off = np.where(row % 2 == 1, bw / 2, 0)
    col = np.floor(((xx + off) % N) / bw).astype(int)
    ly = yy - row * rh
    lx = ((xx + off) % N) - col * bw
    edge = np.minimum.reduce([ly, rh - ly, lx, bw - lx])
    chip = fnoise(1.3) * 4
    is_brick = (edge > mortar * 0.5 + chip * 0.6).astype(float)
    bid = (row * 31 + col * 7) % 97
    tint = np.array([rng.uniform(0.75, 1.15) for _ in range(97)])[bid]
    hue = np.array([rng.uniform(-0.06, 0.06) for _ in range(97)])[bid]
    return is_brick, tint, hue, edge


def brick():
    is_brick, tint, hue, edge = brick_pattern()
    n1 = fnoise(1.0)
    n2 = white()
    shade = tint * (0.85 + 0.2 * (n1 - 0.5) + 0.08 * (n2 - 0.5))
    b = np.stack([(0.55 + hue) * shade, (0.27 + hue * 0.5) * shade, 0.2 * shade], -1)
    m_shade = 0.62 + 0.15 * (n1 - 0.5)
    m = np.stack([m_shade * 0.78, m_shade * 0.75, m_shade * 0.7], -1)
    rgb = b * is_brick[..., None] + m * (1 - is_brick[..., None])
    soot = fnoise(1.9)
    rgb *= (1 - 0.45 * np.clip(soot - 0.5, 0, 1) * 2.0)[..., None]
    grime = blur(fnoise(1.2, aspect=(12.0, 1.0)), 1.0)
    rgb *= (0.85 + 0.2 * grime)[..., None]
    gray = rgb.mean(-1, keepdims=True)
    rgb = rgb * 0.75 + gray * 0.25
    h = is_brick * (0.8 + 0.1 * n1) + 0.15 * n2 * is_brick
    return rgb, h


def plaster():
    low = fnoise(1.9)
    mid = fnoise(1.2)
    fallen = blur((fnoise(1.6) > 0.68).astype(float), 1.5)
    br, bh = brick()
    shade = 0.82 + 0.18 * (low - 0.5) + 0.07 * (mid - 0.5) + 0.04 * (white() - 0.5)
    streak = blur(fnoise(1.2, aspect=(14.0, 1.0)), 1.0)
    shade -= 0.25 * np.clip(streak - 0.55, 0, 1) * 2
    pl = colorize((0.72, 0.66, 0.56), shade)
    pl -= 0.15 * blur(cracks(4, 1), 0.8)[..., None]
    f = fallen[..., None]
    rgb = pl * (1 - f) + br * 0.9 * f
    h = (0.9 + 0.1 * mid) * (1 - fallen) + bh * 0.5 * fallen
    return rgb, h


def asphalt():
    low = fnoise(1.7)
    agg = white()
    agg2 = blur((white() > 0.93).astype(float), 0.5)
    cr = blur(cracks(9, 2, (120, 400)), 0.8)
    patches = blur((fnoise(1.8) > 0.72).astype(float), 3)
    shade = 0.26 + 0.07 * (low - 0.5) + 0.08 * (agg - 0.5) + 0.18 * agg2
    shade += 0.05 * patches
    shade -= 0.12 * cr
    rgb = colorize((1.0, 0.98, 0.95), shade)
    h = 0.4 * agg + 0.6 * agg2 - 1.2 * cr
    return rgb, h


def pavement():
    tiles, gap = 4, 4
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    s = N / tiles
    lx, ly = xx % s, yy % s
    edge = np.minimum.reduce([lx, s - lx, ly, s - ly])
    tile = (edge > gap + fnoise(1.4) * 3).astype(float)
    tid = (np.floor(xx / s) * 5 + np.floor(yy / s) * 3).astype(int) % 16
    tint = np.array([rng.uniform(0.85, 1.1) for _ in range(16)])[tid]
    crete, ch = concrete()
    rgb = crete * (tint * 1.05)[..., None] * tile[..., None] + 0.18 * (1 - tile[..., None])
    h = ch * 0.4 * tile + tile * 0.6
    return rgb, h


def sandbag():
    yy, xx = np.mgrid[0:N, 0:N].astype(float)
    weave = 0.5 + 0.25 * np.sin(xx * np.pi / 2) * np.sign(np.sin(yy * np.pi / 4)) + 0.25 * np.sin(yy * np.pi / 2)
    dirt = fnoise(1.6)
    shade = 0.8 + 0.12 * (weave - 0.5) + 0.25 * (fnoise(1.0) - 0.5)
    shade -= 0.3 * np.clip(dirt - 0.55, 0, 1) * 2
    rgb = colorize((0.62, 0.55, 0.4), shade)
    h = weave * 0.6 + 0.4 * fnoise(1.0)
    return rgb, h


def rust():
    char = fnoise(1.3)
    rmask = blur((fnoise(1.5) > 0.55).astype(float), 2)
    flakes = white()
    c_char = colorize((0.12, 0.1, 0.09), 0.8 + 0.5 * (char - 0.5))
    c_rust = colorize((0.48, 0.24, 0.1), 0.75 + 0.5 * (fnoise(1.0) - 0.5) + 0.15 * (flakes - 0.5))
    m = rmask[..., None]
    rgb = c_char * (1 - m) + c_rust * m
    h = 0.5 * char + 0.4 * rmask * flakes
    return rgb, h


def metal():
    base = fnoise(1.2)
    scratches = blur(np.clip(cracks(40, 1, (20, 60)), 0, 1), 0.3)
    shade = 0.85 + 0.15 * (base - 0.5) + 0.35 * scratches
    rgb = colorize((0.16, 0.16, 0.15), shade)
    h = 0.3 * base - 0.3 * scratches
    return rgb, h


def camo():
    # загальний плямистий камуфляж сіро-оливкових відтінків (не відтворює реальні зразки)
    a, b, c = fnoise(1.45), fnoise(1.4), fnoise(1.5)
    rgb = np.ones((N, N, 3)) * np.array([0.36, 0.37, 0.32])
    rgb[a > 0.58] = [0.27, 0.29, 0.24]
    rgb[(b > 0.62) & (a <= 0.58)] = [0.44, 0.43, 0.37]
    rgb[c > 0.7] = [0.18, 0.18, 0.17]
    yy, xx = np.mgrid[0:N, 0:N]
    weave = 0.5 + 0.5 * np.sin(xx * np.pi / 1.5) * np.sin(yy * np.pi / 1.5)
    rgb *= (0.92 + 0.1 * weave + 0.08 * (fnoise(1.0) - 0.5))[..., None]
    h = 0.5 * weave + 0.5 * fnoise(1.3)
    return rgb, h


if __name__ == "__main__":
    for name, fn, strength in [
        ("concrete", concrete, 3.0), ("brick", brick, 4.0), ("plaster", plaster, 3.0),
        ("asphalt", asphalt, 2.5), ("pavement", pavement, 3.0), ("sandbag", sandbag, 2.5),
        ("rust", rust, 3.0), ("metal", metal, 1.5), ("camo", camo, 1.5),
    ]:
        rgb, h = fn()
        save(name, rgb, h, strength)
