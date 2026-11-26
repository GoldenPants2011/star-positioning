#!/usr/bin/env python3
"""月面几何验证：从"亮部"反推月面几何中心。

问题：非满月时，检测到的亮部质心 ≠ 月面中心（半月时理论偏移 4R/3π ≈ 0.42R）。
本脚本用 RANSAC 拟合**月缘圆**（可见的圆弧边界），再比较：
  - 圆拟合中心 vs 亮部质心
  - 亮部面积占比（相位 k）
用于确定 App 里应该用"月缘圆心"而不是"亮部质心"做姿态解算。

用法：python moon_geometry.py <图片> [--thr 0.2] [--out 叠加图]
"""
import argparse
import math
import os
import sys

import cv2
import numpy as np


def load_gray(path):
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise SystemExit(f"读不到图片：{path}")
    return img


def limb_mask(gray):
    """亮部掩膜：取最大连通域（月亮），阈值用 Otsu。"""
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    n, lab, stats, cen = cv2.connectedComponentsWithStats(th, 8)
    if n <= 1:
        return None
    best = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    mask = (lab == best).astype(np.uint8) * 255
    return mask


def boundary_points(mask):
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cnts:
        return None
    c = max(cnts, key=cv2.contourArea).reshape(-1, 2).astype(np.float64)
    return c


def circle_from_3p(p1, p2, p3):
    ax, ay = p1; bx, by = p2; cx, cy = p3
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-9:
        return None
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    r = math.hypot(ax - ux, ay - uy)
    return ux, uy, r


def ransac_circle(pts, iters=4000, tol=2.5, rng=None):
    """RANSAC 拟合圆；点集是掩膜边界（含月缘弧 + 明暗界线）。"""
    rng = rng or np.random.default_rng(7)
    n = len(pts)
    if n < 10:
        return None
    best = None
    best_in = -1
    for _ in range(iters):
        idx = rng.choice(n, 3, replace=False)
        c = circle_from_3p(pts[idx[0]], pts[idx[1]], pts[idx[2]])
        if c is None:
            continue
        cx, cy, r = c
        d = np.abs(np.hypot(pts[:, 0] - cx, pts[:, 1] - cy) - r)
        inl = int((d < tol).sum())
        if inl > best_in:
            best_in = inl
            best = c
    if best is None:
        return None
    # 用内点做最小二乘精修（Kasa 拟合）
    cx, cy, r = best
    d = np.abs(np.hypot(pts[:, 0] - cx, pts[:, 1] - cy) - r)
    sel = pts[d < tol]
    if len(sel) >= 10:
        x = sel[:, 0]; y = sel[:, 1]
        A = np.c_[2 * x, 2 * y, np.ones(len(x))]
        b = x * x + y * y
        sol, *_ = np.linalg.lstsq(A, b, rcond=None)
        cx, cy = sol[0], sol[1]
        r = math.sqrt(sol[2] + cx * cx + cy * cy)
    return cx, cy, r, best_in


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    gray = load_gray(args.image)
    mask = limb_mask(gray)
    if mask is None:
        raise SystemExit("找不到月亮掩膜")
    pts = boundary_points(mask)
    if pts is None:
        raise SystemExit("找不到边界")

    fit = ransac_circle(pts)
    if fit is None:
        raise SystemExit("圆拟合失败")
    cx, cy, r, inliers = fit
    inlier_frac = inliers / len(pts)

    ys, xs = np.nonzero(mask)
    lit_cx, lit_cy = xs.mean(), ys.mean()
    dist = math.hypot(lit_cx - cx, lit_cy - cy)
    lit_area = len(xs)
    disk_area = math.pi * r * r
    k = lit_area / disk_area

    print(f"图片: {os.path.basename(args.image)}  {gray.shape[1]}x{gray.shape[0]}")
    print(f"月缘圆: 中心=({cx:.1f},{cy:.1f}) 半径={r:.1f}px  边界内点={inliers}/{len(pts)} ({inlier_frac:.0%})")
    print(f"亮部质心: ({lit_cx:.1f},{lit_cy:.1f})")
    print(f"质心偏移: {dist:.1f}px = {dist / r:.3f}R")
    print(f"亮部占比 k = {k:.3f}（满月≈1.0；半月≈0.5）")
    if k < 0.98:
        # 半圆近似：质心偏移 ≈ 4R/(3π) ≈ 0.424R
        print(f"理论对照: 半月偏移 0.424R = {0.424 * r:.1f}px；实测/理论 = {dist / (0.424 * r):.2f}")
    if args.out:
        vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        cv2.circle(vis, (int(round(cx)), int(round(cy))), int(round(r)), (0, 255, 0), 2)
        cv2.drawMarker(vis, (int(round(cx)), int(round(cy))), (0, 255, 0), cv2.MARKER_CROSS, 24, 2)
        cv2.drawMarker(vis, (int(round(lit_cx)), int(round(lit_cy))), (0, 0, 255), cv2.MARKER_CROSS, 24, 2)
        cv2.line(vis, (int(cx), int(cy)), (int(lit_cx), int(lit_cy)), (0, 0, 255), 2)
        cv2.imwrite(args.out, vis)
        print(f"叠加图: {args.out}")


if __name__ == "__main__":
    sys.exit(main())
