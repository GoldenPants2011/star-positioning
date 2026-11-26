#!/usr/bin/env python3
"""用真实满月照片合成不同相位，量化"月缘弧拟合圆心"的精度。

问题：非满月时亮部质心 ≠ 月面几何中心（半月偏移 4R/3π≈0.424R）。
本脚本验证：只用可见的月缘弧（凸包）拟合圆，能不能把几何中心找回来，精度随相位如何变化。

相位用球面模型精确生成（n·s>0 为亮），避免"椭圆近似"在 k<0.5 时出错。
"""
import math
import sys

import cv2
import numpy as np


def load_gray(path):
    img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    if img is None:
        raise SystemExit(f"读不到 {path}")
    if img.ndim == 3 and img.shape[2] == 4:
        return cv2.cvtColor(img, cv2.COLOR_BGRA2GRAY)
    if img.ndim == 3:
        return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return img


def full_disk(gray):
    blur = cv2.GaussianBlur(gray, (7, 7), 0)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(th, 8)
    best = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    mask = (lab == best).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((11, 11), np.uint8))
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    pts = max(cnts, key=cv2.contourArea).reshape(-1, 2).astype(np.float64)
    x, y = pts[:, 0], pts[:, 1]
    A = np.c_[2 * x, 2 * y, np.ones(len(x))]
    b = x * x + y * y
    sol, *_ = np.linalg.lstsq(A, b, rcond=None)
    cx, cy = sol[0], sol[1]
    r = math.sqrt(sol[2] + cx * cx + cy * cy)
    return mask, (cx, cy, r)


def phase_mask(shape, cx, cy, r, k, solar_angle_deg=0.0):
    """球面模型：亮部 = 法向与太阳方向夹角 <90°。k=(1+cosθ)/2。"""
    h, w = shape
    yy, xx = np.mgrid[0:h, 0:w]
    u = (xx - cx) / r
    v = (yy - cy) / r
    rr = u * u + v * v
    disk = rr <= 1.0
    wwd = np.sqrt(np.clip(1.0 - rr, 0.0, 1.0))
    theta = math.acos(max(-1.0, min(1.0, 2 * k - 1)))   # 相位角
    t = math.radians(solar_angle_deg)
    su, sv, sw = math.sin(theta) * math.cos(t), math.sin(theta) * math.sin(t), math.cos(theta)
    lit = disk & ((u * su + v * sv + wwd * sw) > 0)
    return (lit * 255).astype(np.uint8)


def circle_lsq(pts):
    x, y = pts[:, 0], pts[:, 1]
    A = np.c_[2 * x, 2 * y, np.ones(len(x))]
    b = x * x + y * y
    sol, *_ = np.linalg.lstsq(A, b, rcond=None)
    cx, cy = sol[0], sol[1]
    r = math.sqrt(sol[2] + cx * cx + cy * cy)
    return cx, cy, r


def ransac_circle(pts, iters=3000, tol=3.0, seed=7):
    """凸月时凸包带一条弦，普通最小二乘会被拉偏；RANSAC 只认落在同一圆上的点。"""
    rng = np.random.default_rng(seed)
    best = None
    best_in = -1
    n = len(pts)
    if n < 12:
        return None
    for _ in range(iters):
        i = rng.choice(n, 3, replace=False)
        p1, p2, p3 = pts[i[0]], pts[i[1]], pts[i[2]]
        ax, ay = p1; bx, by = p2; cx3, cy3 = p3
        d = 2 * (ax * (by - cy3) + bx * (cy3 - ay) + cx3 * (ay - by))
        if abs(d) < 1e-9:
            continue
        ux = ((ax * ax + ay * ay) * (by - cy3) + (bx * bx + by * by) * (cy3 - ay) + (cx3 * cx3 + cy3 * cy3) * (ay - by)) / d
        uy = ((ax * ax + ay * ay) * (cx3 - bx) + (bx * bx + by * by) * (ax - cx3) + (cx3 * cx3 + cy3 * cy3) * (bx - ax)) / d
        r = math.hypot(ax - ux, ay - uy)
        dist = np.abs(np.hypot(pts[:, 0] - ux, pts[:, 1] - uy) - r)
        inl = int((dist < tol).sum())
        if inl > best_in:
            best_in = inl
            best = (ux, uy, r)
    if best is None:
        return None
    ux, uy, r = best
    dist = np.abs(np.hypot(pts[:, 0] - ux, pts[:, 1] - uy) - r)
    sel = pts[dist < tol]
    if len(sel) >= 10:
        return circle_lsq(sel)
    return best


def hull_pts(mask):
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cnts:
        return None
    c = max(cnts, key=cv2.contourArea)
    hull = cv2.convexHull(c).reshape(-1, 2).astype(np.float64)
    return hull if len(hull) >= 12 else None


def coverage(hull, cx, cy):
    ang = np.degrees(np.arctan2(hull[:, 1] - cy, hull[:, 0] - cx))
    ang = np.sort(np.mod(ang, 360))
    if len(ang) < 2:
        return 0.0
    gaps = np.diff(np.r_[ang, ang[0] + 360])
    return 360.0 - float(gaps.max())


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "../star-positioning-real-images/moon-full.png"
    gray = load_gray(src)
    mask, (cx0, cy0, r0) = full_disk(gray)
    print(f"满月真值：中心 ({cx0:.1f},{cy0:.1f}) 半径 {r0:.1f}px 图像 {gray.shape[1]}x{gray.shape[0]}")
    print("k(亮部) | 亮部质心偏移 | 月缘弧拟合圆心误差 | 半径误差 | 可见弧段")
    for k in [0.98, 0.75, 0.5, 0.3, 0.15, 0.08]:
        pm = phase_mask(gray.shape, cx0, cy0, r0, k)
        hull = hull_pts(pm)
        if hull is None:
            print(f"{k:.2f} | 拟合失败")
            continue
        fit = ransac_circle(hull)
        if fit is None:
            print(f"{k:.2f} | RANSAC 失败")
            continue
        cx, cy, r = fit
        ys, xs = np.nonzero(pm)
        lit_cx, lit_cy = xs.mean(), ys.mean()
        shift = math.hypot(lit_cx - cx0, lit_cy - cy0)
        err = math.hypot(cx - cx0, cy - cy0)
        print(
            f"{k:4.2f} | {shift:7.1f}px ({shift / r0:.3f}R) | "
            f"{err:6.2f}px ({err / r0:.4f}R) | {r - r0:+6.2f}px | {coverage(hull, cx, cy):5.0f}°",
        )


if __name__ == "__main__":
    main()

