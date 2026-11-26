#!/usr/bin/env python3
"""内参标定：从采集包里读帧，检测点阵，用 cv2.calibrateCamera 求 fx/fy/cx/cy + 畸变。

用法：
  python calibrate_intrinsics.py <session 目录或 zip> [--cols 9] [--rows 13] [--spacing-mm 20]
  python calibrate_intrinsics.py --selftest          # 用合成点阵自检（不需要真机）

注意：RAW 帧在 App 里按 2×2 合并成一半分辨率保存（source=raw16），
输出的内参会自动乘 2 换回全分辨率，并在 JSON 里注明。
"""
import argparse
import json
import os
import struct
import sys
import tempfile
import zipfile

import numpy as np

try:
    import cv2
except ImportError:
    print("缺少 opencv，先跑：python -m pip install opencv-python-headless")
    sys.exit(2)


# ---------- 帧格式 ----------

def read_spf(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"SPF1":
        raise ValueError(f"{path}: 不是 SPF1 帧")
    ver, bd, w, h = struct.unpack("<HHII", data[4:16])
    if bd == 16:
        px = np.frombuffer(data[16:16 + w * h * 2], dtype="<u2").reshape(h, w).astype(np.float32)
    else:
        px = np.frombuffer(data[16:16 + w * h], dtype=np.uint8).reshape(h, w).astype(np.float32)
    return px, bd


def to_gray8(px, bd):
    if bd == 16:
        hi = np.percentile(px, 99.5)
        scale = 255.0 / max(hi, 1.0)
        return np.clip(px * scale, 0, 255).astype(np.uint8)
    return np.clip(px, 0, 255).astype(np.uint8)


# ---------- 点阵检测 ----------

def detect_dots(gray, cols, rows, debug=None):
    """返回按行优先排序的 (N,2) 点阵，或者 None。"""
    g = cv2.GaussianBlur(gray, (5, 5), 0)
    thr = cv2.adaptiveThreshold(
        g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 41, 12
    )
    n, lab, stats, cent = cv2.connectedComponentsWithStats(thr, 8)
    pts = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 12:
            continue
        if w <= 0 or h <= 0:
            continue
        aspect = max(w, h) / min(w, h)
        if aspect > 2.2:            # 不像圆点
            continue
        if area > 0.01 * gray.size:  # 太大（阴影/污渍）
            continue
        pts.append(cent[i])
    if debug is not None:
        debug["candidates"] = len(pts)
    if len(pts) != cols * rows:
        return None

    pts = np.array(pts, dtype=np.float32)

    # 估计点阵的两个方向（倾斜拍摄时"行"不是水平的，直接按 y 聚类会错）
    return _order_grid(pts, cols, rows, debug)


def _order_grid(pts, cols, rows, debug=None):
    """把无序点阵排成行优先顺序；估计点阵方向后按行/列聚类。"""
    n = len(pts)
    k = min(7, n - 1)
    d = np.linalg.norm(pts[:, None, :] - pts[None, :, :], axis=2)
    np.fill_diagonal(d, np.inf)
    nn = np.argsort(d, axis=1)[:, :k]
    vecs = (pts[nn] - pts[:, None, :]).reshape(-1, 2)
    ang = np.degrees(np.arctan2(vecs[:, 1], vecs[:, 0])) % 180.0
    hist, edges = np.histogram(ang, bins=36, range=(0, 180))
    a1 = (edges[hist.argmax()] + edges[hist.argmax() + 1]) / 2.0
    mask = np.abs(((ang - a1 + 90) % 180) - 90) > 15
    if mask.sum() > 5:
        h2, e2 = np.histogram(ang[mask], bins=36, range=(0, 180))
        a2 = (e2[h2.argmax()] + e2[h2.argmax() + 1]) / 2.0
    else:
        a2 = (a1 + 90.0) % 180.0

    for row_ang in (a1, a2, (a1 + 90.0) % 180.0, (a2 + 90.0) % 180.0):
        th = np.radians(row_ang)
        rot = np.array([[np.cos(th), np.sin(th)], [-np.sin(th), np.cos(th)]], np.float32)
        rp = pts @ rot.T
        out = _cluster_rows(rp, pts, cols, rows)
        if out is not None:
            return out
    if debug is not None:
        debug["order_failed"] = True
    return None


def _cluster_rows(rp, pts, cols, rows):
    """按行聚类：行距用 (max-min)/(rows-1) 估计，容差取半个行距。"""
    ys = rp[:, 1]
    order = np.argsort(ys)
    ys_sorted = ys[order]
    span = float(ys_sorted[-1] - ys_sorted[0])
    if span <= 0:
        return None
    row_step = span / (rows - 1) if rows > 1 else span
    tol = 0.5 * row_step

    groups = []
    cur = [0]
    for i in range(1, len(ys_sorted)):
        if abs(ys_sorted[i] - np.mean(ys_sorted[cur])) > tol:
            groups.append(cur)
            cur = [i]
        else:
            cur.append(i)
    groups.append(cur)

    if len(groups) != rows or any(len(g) != cols for g in groups):
        return None

    out = []
    for g in groups:
        idx = order[np.array(g)]
        idx = idx[np.argsort(rp[idx, 0])]
        out.extend(idx.tolist())
    return pts[out]


def calibrate(frames, cols, rows, spacing_mm):
    objp = np.zeros((rows * cols, 3), np.float32)
    objp[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * spacing_mm

    obj_pts, img_pts, used, skipped = [], [], [], []
    h = w = 0
    for path, px, bd in frames:
        gray = to_gray8(px, bd)
        h, w = gray.shape[:2]
        dbg = {}
        pts = detect_dots(gray, cols, rows, dbg)
        if pts is None:
            skipped.append(os.path.basename(path))
            continue
        obj_pts.append(objp.copy())
        img_pts.append(pts)
        used.append(os.path.basename(path))

    if len(used) < 4:
        return None, {"used": used, "skipped": skipped,
                      "error": f"可用帧只有 {len(used)} 张，至少要 4 张"}

    rms, K, dist, rvecs, tvecs = cv2.calibrateCamera(
        obj_pts, img_pts, (w, h), None, None,
        flags=cv2.CALIB_FIX_K3,
    )
    fx, fy = float(K[0, 0]), float(K[1, 1])
    cx, cy = float(K[0, 2]), float(K[1, 2])
    return {
        "rms_px": float(rms),
        "fx": fx, "fy": fy, "cx": cx, "cy": cy,
        "dist": [float(d) for d in dist.ravel()],
        "width": int(w), "height": int(h),
        "frames_used": len(used), "frames_skipped": len(skipped),
        "used": used, "skipped": skipped,
    }, None


def load_frames(path):
    frames = []
    if os.path.isdir(path):
        fdir = os.path.join(path, "frames")
        src = os.path.join(path, "session.json")
        source = "raw16"
        if os.path.exists(src):
            try:
                source = json.load(open(src, encoding="utf-8")).get("capture", {}).get("mode", "raw16")
            except Exception:
                pass
        names = sorted(n for n in os.listdir(fdir) if n.endswith(".bin"))
        for n in names:
            px, bd = read_spf(os.path.join(fdir, n))
            frames.append((n, px, bd))
        return frames, source
    tmp = tempfile.mkdtemp(prefix="calib_")
    with zipfile.ZipFile(path) as z:
        z.extractall(tmp)
    frames, source = load_frames(tmp)
    return frames, source


# ---------- 合成自检 ----------

def selftest():
    w, h = 1600, 1200
    f_true, cx_true, cy_true = 1900.0, 806.0, 597.0
    cols, rows, spacing = 9, 13, 20.0
    K = np.array([[f_true, 0, cx_true], [0, f_true, cy_true], [0, 0, 1]], np.float64)
    dist_true = np.array([0.01, -0.02, 0.0, 0.0, 0.0])
    objp = np.zeros((rows * cols, 3), np.float32)
    objp[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * spacing

    tmpdir = tempfile.mkdtemp(prefix="calib_selftest_")
    os.makedirs(os.path.join(tmpdir, "frames"), exist_ok=True)
    rng = np.random.default_rng(7)
    n_ok = 0
    attempts = 0
    while n_ok < 10 and attempts < 400:
        attempts += 1
        rvec = np.array([rng.uniform(-0.45, 0.45), rng.uniform(-0.45, 0.45), rng.uniform(-0.35, 0.35)])
        tvec = np.array([-cols * spacing / 2 + rng.uniform(-25, 25),
                         -rows * spacing / 2 + rng.uniform(-25, 25),
                         rng.uniform(420, 620)])
        proj, _ = cv2.projectPoints(objp, rvec, tvec, K, dist_true)
        proj = proj.reshape(-1, 2)
        m = 40
        if proj.min() < m or proj[:, 0].max() > w - m or proj[:, 1].max() > h - m:
            continue
        img = np.full((h, w), 235, np.uint8)
        for (x, y) in proj:
            cv2.circle(img, (int(round(x)), int(round(y))), 5, 30, -1)
        img = cv2.GaussianBlur(img, (3, 3), 0)
        with open(os.path.join(tmpdir, "frames", f"frame_{n_ok:03d}.bin"), "wb") as fh:
            fh.write(b"SPF1")
            fh.write(struct.pack("<HHII", 1, 8, w, h))
            fh.write(img.tobytes())
        n_ok += 1
    print(f"自检：生成 {n_ok} 张合成帧（尝试 {attempts} 次）-> {tmpdir}")
    frames, source = load_frames(tmpdir)
    res, err = calibrate(frames, cols, rows, spacing)
    if res is None:
        print("自检失败：", err)
        # 打印每帧诊断
        for name, px, bd in frames:
            gray = to_gray8(px, bd)
            dbg = {}
            detect_dots(gray, cols, rows, dbg)
            print("  ", name, dbg)
        return 1
    print(json.dumps({k: res[k] for k in ("rms_px", "fx", "fy", "cx", "cy", "frames_used")}, ensure_ascii=False, indent=2))
    print(f"真值：fx={f_true:.1f} fy={f_true:.1f} cx={cx_true:.1f} cy={cy_true:.1f}")
    ok = abs(res["fx"] - f_true) / f_true < 0.02 and abs(res["cx"] - cx_true) < 15
    print("自检通过 ✅" if ok else "自检偏差过大 ❌")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path", nargs="?")
    ap.add_argument("--cols", type=int, default=9)
    ap.add_argument("--rows", type=int, default=13)
    ap.add_argument("--spacing-mm", type=float, default=20.0)
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--out", default="calibration.json")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())
    if not args.path:
        ap.print_help()
        sys.exit(2)

    frames, source = load_frames(args.path)
    print(f"读入 {len(frames)} 帧，source={source}")
    res, err = calibrate(frames, args.cols, args.rows, args.spacing_mm)
    if res is None:
        print("标定失败：", json.dumps(err, ensure_ascii=False))
        sys.exit(1)

    scale = 2.0 if source == "raw16" else 1.0
    if scale != 1.0:
        for k in ("fx", "fy", "cx", "cy"):
            res[k] *= scale
        res["width"] *= int(scale)
        res["height"] *= int(scale)
    res["scale_applied"] = scale
    res["note"] = "fx/fy/cx/cy 已换算到全分辨率像素坐标"
    print(json.dumps(res, ensure_ascii=False, indent=2))
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    print(f"已写出 {args.out}")


if __name__ == "__main__":
    main()


