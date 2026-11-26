#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later

"""生成 A4 打印用点阵标定板（9 列 × 13 行，20 mm 间距，点径 3.6 mm）。

与 tools/calibrate_intrinsics.py 的默认参数一致：
    --cols 9 --rows 13 --spacing-mm 20

重要：**标定页不能有任何文字/十字/色块**——检测器要求画面里恰好 117 个近圆连通域，
文字笔画和十字会被当成点，导致计数不符（2026-11-19 实测：302 个候选 → 检测失败）。
所以第 1 页只有点阵 + 一条细线比例尺（细长形会被长宽比过滤），说明放第 2 页。

用法：python tools/make_dot_grid_a4.py [输出目录]
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

DPI = 300
MM = DPI / 25.4
A4_W, A4_H = int(210 * MM), int(297 * MM)

COLS, ROWS = 9, 13
SPACING_MM = 20.0
DOT_DIAM_MM = 3.6

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    r"C:\Windows\Fonts\arial.ttf",
]


def font(size_pt):
    for p in FONT_CANDIDATES:
        if os.path.isfile(p):
            try:
                return ImageFont.truetype(p, int(size_pt * DPI / 72))
            except Exception:
                continue
    return ImageFont.load_default()


def pattern_page():
    img = Image.new("L", (A4_W, A4_H), 255)
    d = ImageDraw.Draw(img)
    grid_w = (COLS - 1) * SPACING_MM * MM
    grid_h = (ROWS - 1) * SPACING_MM * MM
    x0 = (A4_W - grid_w) / 2
    y0 = (A4_H - grid_h) / 2
    r = DOT_DIAM_MM * MM / 2
    for j in range(ROWS):
        for i in range(COLS):
            cx = x0 + i * SPACING_MM * MM
            cy = y0 + j * SPACING_MM * MM
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=0)
    # 细线比例尺：横线 + 刻度都是细长形，会被检测器的长宽比过滤掉
    sx = x0
    sy = A4_H - 20 * MM
    d.line([sx, sy, sx + 100 * MM, sy], fill=0, width=int(0.4 * MM))
    for t in (0, 50, 100):
        bx = sx + t * MM
        d.line([bx, sy - 3 * MM, bx, sy + 3 * MM], fill=0, width=int(0.25 * MM))
    return img


def info_page():
    img = Image.new("L", (A4_W, A4_H), 255)
    d = ImageDraw.Draw(img)
    f_t = font(20)
    f_h = font(13)
    f_b = font(10.5)
    x = 22 * MM
    y = 24 * MM
    d.text((x, y), "点阵标定板 · 打印与拍摄说明", fill=0, font=f_t)
    y += 16 * MM
    blocks = [
        ("规格", [
            "9 列 × 13 行 = 117 个圆点；点间距 20 mm；点径 3.6 mm；点阵区域 160 × 240 mm。",
            "第 1 页是标定页，除点阵和一条细线比例尺外没有任何标记——请勿在标定页上写字。",
        ]),
        ("打印", [
            "用 A4 纸，缩放选择「实际大小 / 100%」，不要「适合页面 / 缩放」。",
            "打印后量第 1 页底部的 100 mm 比例尺：应约等于 100 mm，误差 <1 mm 才可用。",
            "建议贴在硬质泡沫板或厚卡纸上，保证平整、不翘曲。",
        ]),
        ("拍摄", [
            "白天均匀光照（阴天窗边最稳），避免直射反光和阴影。",
            "点阵占画面 1/3–2/3；拍 12 张：正对、四角倾斜、远近各几张。",
            "每张都让全部 117 个点可见（不要被裁掉），并保持清晰不虚。",
        ]),
        ("处理", [
            "采集包导出后用：python calibrate_intrinsics.py <session 目录> --cols 9 --rows 13 --spacing-mm 20",
            "工具会输出 fx/fy/cx/cy 与畸变参数；RMS 重投影误差 <1 px 视为合格。",
        ]),
    ]
    for title, lines in blocks:
        d.text((x, y), title, fill=0, font=f_h)
        y += 8 * MM
        for line in lines:
            d.text((x + 4 * MM, y), "· " + line, fill=0, font=f_b)
            y += 7 * MM
        y += 4 * MM
    return img


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else "calibration-pattern"
    os.makedirs(outdir, exist_ok=True)
    p1 = pattern_page()
    p2 = info_page()
    png = os.path.join(outdir, "dot-grid-9x13-20mm-A4.png")
    pdf = os.path.join(outdir, "dot-grid-9x13-20mm-A4.pdf")
    p1.save(png, dpi=(DPI, DPI))
    p1.convert("RGB").save(pdf, "PDF", resolution=DPI, save_all=True,
                           append_images=[p2.convert("RGB")])
    print(f"{png}  {os.path.getsize(png) // 1024} KB")
    print(f"{pdf}  {os.path.getsize(pdf) // 1024} KB（2 页：标定页 + 说明页）")


if __name__ == "__main__":
    main()
