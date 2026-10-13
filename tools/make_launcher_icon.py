"""Generate legacy launcher icon PNGs (API 24-25 fallback).

API 26+ uses the adaptive icon in res/mipmap-anydpi-v26/ic_launcher.xml; older
launchers cannot inflate a VectorDrawable from another package, so they need
real bitmaps. The design mirrors the vector foreground: a dark aurora tile with
a glass panel on top.
"""

import os
import numpy as np
from PIL import Image, ImageDraw

OUT_ROOT = r"C:\Users\Yeech\Documents\DeepSeek Harness\ETS100-Auto-PK\app\src\main\res"

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def lerp(a, b, t):
    return a + (b - a) * t


def diagonal_gradient(size, stops):
    """stops: list of (offset, (r, g, b)) along the top-left -> bottom-right axis."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    t = (x + y) / (2.0 * (size - 1))
    out = np.zeros((size, size, 3), dtype=np.float32)
    for i in range(len(stops) - 1):
        o0, c0 = stops[i]
        o1, c1 = stops[i + 1]
        band = (t >= o0) & (t <= o1)
        if not band.any():
            continue
        local = (t[band] - o0) / max(o1 - o0, 1e-6)
        for ch in range(3):
            out[..., ch][band] = lerp(c0[ch], c1[ch], local)
    out[t < stops[0][0]] = stops[0][1]
    out[t > stops[-1][0]] = stops[-1][1]
    return out


def radial_blob(size, cx, cy, radius, color, alpha):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    d = np.sqrt(((x - cx * size) / (radius * size)) ** 2 + ((y - cy * size) / (radius * size)) ** 2)
    falloff = np.clip(1.0 - d, 0.0, 1.0) ** 2 * alpha
    layer = np.zeros((size, size, 4), dtype=np.float32)
    layer[..., 0], layer[..., 1], layer[..., 2] = color
    layer[..., 3] = falloff
    return layer


def over(base, top):
    """Standard source-over alpha compositing on float RGBA arrays."""
    ba, ta = base[..., 3:4], top[..., 3:4]
    out_a = ta + ba * (1 - ta)
    safe = np.where(out_a == 0, 1.0, out_a)
    out_rgb = (top[..., :3] * ta + base[..., :3] * ba * (1 - ta)) / safe
    out = np.empty_like(base)
    out[..., :3] = out_rgb
    out[..., 3:4] = out_a
    return out


def rounded_mask(size, box, radius):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(box, radius=radius, fill=255)
    return np.asarray(mask, dtype=np.float32) / 255.0


def rgba_from_rgb(rgb):
    out = np.ones((rgb.shape[0], rgb.shape[1], 4), dtype=np.float32)
    out[..., :3] = rgb
    return out


def build(size):
    s = float(size)

    # 1. 深色底：整块圆角方形 + 竖向渐变
    bg = np.zeros((size, size, 4), dtype=np.float32)
    y = np.mgrid[0:size, 0:size][0].astype(np.float32) / (size - 1)
    for ch, (top, bottom) in enumerate(zip((0x18, 0x24, 0x42), (0x07, 0x0C, 0x18))):
        bg[..., ch] = lerp(top, bottom, y)
    corner = max(2, int(round(0.22 * s)))
    box = (0, 0, size - 1, size - 1)
    bg[..., 3] = rounded_mask(size, box, corner)

    # 2. 极光光斑
    bg = over(bg, radial_blob(size, 0.18, 0.14, 0.80, (0x3B / 255, 0x82 / 255, 0xF6 / 255), 0.80))
    bg = over(bg, radial_blob(size, 0.90, 0.26, 0.70, (0x8B / 255, 0x5C / 255, 0xF6 / 255), 0.72))
    bg = over(bg, radial_blob(size, 0.46, 0.86, 0.85, (0x22 / 255, 0xD3 / 255, 0xEE / 255), 0.50))

    # 3. 玻璃面板：对角渐变 + 圆角
    inset = 0.26 * s
    tile_box = (int(round(inset)), int(round(inset)), int(round(s - inset)), int(round(s - inset)))
    tile_size = tile_box[2] - tile_box[0] + 1
    grad = diagonal_gradient(
        tile_size,
        [
            (0.0, (0x3B / 255, 0x82 / 255, 0xF6 / 255)),
            (0.55, (0x7C / 255, 0x5C / 255, 0xF0 / 255)),
            (1.0, (0x22 / 255, 0xD3 / 255, 0xEE / 255)),
        ],
    )
    tile_rgba = rgba_from_rgb(grad)
    tile_mask = Image.new("L", (tile_size, tile_size), 0)
    ImageDraw.Draw(tile_mask).rounded_rectangle(
        (0, 0, tile_size - 1, tile_size - 1), radius=int(round(0.13 * s)), fill=255
    )
    tile_rgba[..., 3] = np.asarray(tile_mask, dtype=np.float32) / 255.0

    layer = np.zeros((size, size, 4), dtype=np.float32)
    layer[tile_box[1]:tile_box[3] + 1, tile_box[0]:tile_box[2] + 1] = tile_rgba
    bg = over(bg, layer)

    # 4. 顶部高光
    hl = np.zeros((size, size, 4), dtype=np.float32)
    hl[..., :3] = 1.0
    hl[..., 3] = rounded_mask(
        size,
        (int(0.37 * s), int(0.37 * s), int(0.63 * s), int(0.44 * s)),
        max(1, int(round(0.035 * s))),
    ) * 0.48
    bg = over(bg, hl)

    # 5. 下半部压暗，做出体积感（只压玻璃面板，用面板蒙版裁一下）
    shade = np.zeros((size, size, 4), dtype=np.float32)
    shade[..., 3] = 0.28
    shade[: int(0.54 * s), :, 3] = 0.0
    clipped = np.zeros_like(shade)
    clipped[tile_box[1]:tile_box[3] + 1, tile_box[0]:tile_box[2] + 1] = shade[
        tile_box[1]:tile_box[3] + 1, tile_box[0]:tile_box[2] + 1
    ]
    bg = over(bg, clipped)

    arr = np.clip(bg * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(arr, mode="RGBA")


def main():
    for name, size in DENSITIES.items():
        folder = os.path.join(OUT_ROOT, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        icon = build(size)
        path = os.path.join(folder, "ic_launcher.png")
        icon.save(path, "PNG", optimize=True)
        print(f"{name:8s} {size:3d}px -> {path}")


if __name__ == "__main__":
    main()
