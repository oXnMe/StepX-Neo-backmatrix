#!/usr/bin/env python3
"""把 GIF/视频预先转换成应用内置的点阵动画（.bsa）。

这个脚本是 App 内 Kotlin 转换管线（Converter.kt / Importer.kt）的桌面镜像，
用来生成随 APK 打包的示例动画，也方便在电脑上反复调参数。

用法：
    python3 tools/convert_demo.py your-clip.gif \
        --out app/src/main/assets/demo_basketball.bsa \
        --name "内置演示" --fps 12 --preview build/demo-preview.png
"""

import argparse
import os
import struct
import sys

from PIL import Image, ImageSequence

W, H = 17, 23
PIXELS = W * H

# 与 App 默认值保持一致
DEFAULTS = dict(
    black=0.08,
    white=0.40,
    gamma=1.30,
    levels=8,
    invert=True,
    fit="cover",
    zoom=1.0,
    offset_x=0.0,
    offset_y=0.0,
    brightness=1.0,
)


def luma_crop(frame: Image.Image, fit: str, zoom: float, ox: float, oy: float) -> Image.Image:
    """按 App 的取景规则裁出一块，再缩到 17x23（面积平均）。"""
    frame = frame.convert("RGBA")
    bg = Image.new("RGBA", frame.size, (255, 255, 255, 255))
    frame = Image.alpha_composite(bg, frame).convert("RGB")
    sw, sh = frame.size
    grid_aspect = W / H
    src_aspect = sw / sh

    if fit == "cover":
        if src_aspect > grid_aspect:
            ch, cw = sh, sh * grid_aspect
        else:
            cw, ch = sw, sw / grid_aspect
        cw = min(cw / zoom, sw)
        ch = min(ch / zoom, sh)
        cx = sw / 2 + ox * (sw - cw) / 2
        cy = sh / 2 + oy * (sh - ch) / 2
        x0 = min(max(cx - cw / 2, 0), sw - cw)
        y0 = min(max(cy - ch / 2, 0), sh - ch)
        box = (int(x0), int(y0), int(min(sw, x0 + cw)), int(min(sh, y0 + ch)))
        return frame.crop(box).resize((W, H), Image.BOX)

    if fit == "stretch":
        return frame.resize((W, H), Image.BOX)

    # contain：等比放进 17x23，四周留白
    if src_aspect > grid_aspect:
        nw, nh = W, max(1, round(W / src_aspect))
    else:
        nh, nw = H, max(1, round(H * src_aspect))
    canvas = Image.new("RGB", (W, H), (255, 255, 255))
    canvas.paste(frame.resize((nw, nh), Image.BOX), ((W - nw) // 2, (H - nh) // 2))
    return canvas


def to_dots(img: Image.Image, s) -> bytes:
    px = img.load()
    out = bytearray(PIXELS)
    span = s["white"] - s["black"] or 1.0
    levels = max(2, min(256, s["levels"]))
    step = levels - 1
    for y in range(H):
        for x in range(W):
            r, g, b = px[x, y]
            v = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            if s["invert"]:
                v = 1.0 - v
            v = min(1.0, max(0.0, (v - s["black"]) / span))
            v = v ** s["gamma"]
            q = 1.0 if levels >= 256 else round(v * step) / step
            q *= s["brightness"]
            out[y * W + x] = max(0, min(255, int(q * 255 + 0.5)))
    return bytes(out)


def frames_from_gif(path: str, fps: int):
    im = Image.open(path)
    interval = 1000.0 / fps
    acc = 0.0
    due = 0.0
    for frame in ImageSequence.Iterator(im):
        acc += frame.info.get("duration", 40) or 40
        if acc >= due:
            due += interval
            yield frame.copy()


def write_bsa(path: str, frames, name: str, fps: int, loop: bool = True):
    name_bytes = name.encode("utf-8")
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"BSA1")
        f.write(bytes([1]))
        f.write(bytes([1 if loop else 0]))
        f.write(bytes([max(1, min(60, fps))]))
        f.write(bytes([W, H]))
        f.write(struct.pack("<H", len(frames)))
        f.write(struct.pack("<H", len(name_bytes)))
        f.write(name_bytes)
        for fr in frames:
            f.write(fr)
    return len(frames)


def preview_sheet(frames, out_path: str, count: int = 12, scale: int = 12):
    idxs = [i * len(frames) // max(1, count) for i in range(count)]
    cell_w, cell_h = W * scale, H * scale
    sheet = Image.new("RGB", (cell_w * count, cell_h), (14, 14, 17))
    for k, i in enumerate(idxs):
        tile = Image.new("RGB", (W, H))
        fr = frames[i]
        for y in range(H):
            for x in range(W):
                v = fr[y * W + x]
                tile.putpixel((x, y), (v, v, v))
        sheet.paste(tile.resize((cell_w, cell_h), Image.NEAREST), (k * cell_w, 0))
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    sheet.save(out_path)
    return out_path


def load_frames(path: str, fps: int, s):
    """视频用 imageio/ffmpeg 不方便，这里只处理 GIF 与静态图。"""
    if path.lower().endswith((".mp4", ".mov", ".webm")):
        raise SystemExit("桌面脚本只处理 GIF；视频请用 App 内转换（MediaMetadataRetriever）")
    return [to_dots(luma_crop(f, s["fit"], s["zoom"], s["offset_x"], s["offset_y"]), s)
            for f in frames_from_gif(path, fps)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="内置演示")
    ap.add_argument("--fps", type=int, default=12)
    ap.add_argument("--no-loop", action="store_true")
    ap.add_argument("--preview")
    for key, val in DEFAULTS.items():
        if key in ("levels", "fit"):
            continue
        if isinstance(val, bool):
            ap.add_argument(f"--{key.replace('_', '-')}", dest=key, action="store_true", default=val)
            ap.add_argument(f"--no-{key.replace('_', '-')}", dest=key, action="store_false")
        else:
            ap.add_argument(f"--{key.replace('_', '-')}", dest=key, type=float, default=val)
    ap.add_argument("--levels", type=int, default=DEFAULTS["levels"])
    ap.add_argument("--fit", default=DEFAULTS["fit"], choices=["cover", "contain", "stretch"])
    args = ap.parse_args()

    s = {k: getattr(args, k) for k in DEFAULTS}
    s["levels"] = args.levels
    frames = load_frames(args.source, args.fps, s)
    if not frames:
        raise SystemExit("没有取到任何帧")
    n = write_bsa(args.out, frames, args.name, args.fps, not args.no_loop)
    print(f"写出 {args.out}：{n} 帧 · {args.fps}fps · {os.path.getsize(args.out)/1024:.1f} KB")
    if args.preview:
        print("预览：", preview_sheet(frames, args.preview))


if __name__ == "__main__":
    main()
