#!/usr/bin/env python3
"""把采集包里的 raw16 帧转成 PNG（自动拉伸），用于人工回忆/排查。

用法：python tools/bundle_to_images.py <zip> [输出目录]
"""
import json
import os
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def load_frame(z, idx):
    meta = json.loads(z.read(f"frames/frame_{idx:03d}.json"))
    raw = z.read(f"frames/frame_{idx:03d}.bin")
    w, h = meta["width"], meta["height"]
    depth = meta.get("bit_depth", 16)
    if depth == 16:
        # 可能有 16 字节头：按 payload 大小判断
        need = w * h * 2
        buf = raw[-need:] if len(raw) >= need else raw
        arr = np.frombuffer(buf, dtype="<u2").reshape(h, w).astype(np.float32)
        arr /= 65535.0
    else:
        need = w * h
        buf = raw[-need:] if len(raw) >= need else raw
        arr = np.frombuffer(buf, dtype=np.uint8).reshape(h, w).astype(np.float32) / 255.0
    return meta, arr


def render(arr, gamma=0.45, lo_pct=0.2, hi_pct=99.8):
    lo = np.percentile(arr, lo_pct)
    hi = np.percentile(arr, hi_pct)
    span = max(hi - lo, 1e-5)
    v = np.clip((arr - lo) / span, 0, 1) ** gamma
    return (v * 255).astype(np.uint8)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    outdir = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(path)[0] + "-images"
    os.makedirs(outdir, exist_ok=True)
    with zipfile.ZipFile(path) as z:
        names = sorted(n for n in z.namelist() if n.startswith("frames/") and n.endswith(".json"))
        thumbs = []
        for i in range(len(names)):
            meta, arr = load_frame(z, i)
            img = Image.fromarray(render(arr))
            base = os.path.splitext(os.path.basename(path))[0]
            out = os.path.join(outdir, f"{base}-frame{i:03d}.png")
            img.save(out)
            stats = "exp=%.0fms iso=%d 中位=%.4f p99=%.4f" % (
                meta["exposure_ns"] / 1e6, meta["iso"], float(np.median(arr)), float(np.percentile(arr, 99)))
            print(out, stats)
            thumbs.append(img.resize((640, 480), Image.LANCZOS))
        # 拼图
        cols = 3
        rows = (len(thumbs) + cols - 1) // cols
        pad = 10
        sheet = Image.new("L", (cols * 640 + (cols + 1) * pad, rows * 480 + (rows + 1) * pad), 255)
        for i, t in enumerate(thumbs):
            r, c = divmod(i, cols)
            sheet.paste(t, (pad + c * (640 + pad), pad + r * (480 + pad)))
        sheet_path = os.path.join(outdir, os.path.splitext(os.path.basename(path))[0] + "-sheet.png")
        sheet.save(sheet_path)
        print(sheet_path)


if __name__ == "__main__":
    main()
