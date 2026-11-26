import csv
import os
import sys
from openpyxl import Workbook
from openpyxl.styles import Font, Alignment, PatternFill
from openpyxl.utils import get_column_letter

WS = r"D:\Documents\deepseek-harness\default-workspace\star-positioning"
CSV_PATH = os.path.join(WS, "field-inbox", "full_report.csv")
OUT = os.path.join(WS, "field-inbox", "field-inbox-full-report.xlsx")

HEADER = [
    "type", "session_id", "created_local", "camera_id", "frames", "exposures_ms", "iso_list",
    "note", "status", "gnss_lat", "gnss_lon", "gnss_acc_m", "gnss_provider",
    "frame", "exposure_ms", "iso", "detections", "matched", "rms_arcsec", "frame_solved",
    "sun_frames", "sun_resid_mean", "sun_resid_rms",
    "moon_frames", "moon_resid_mean", "moon_resid_rms",
    "moon_fix_lat", "moon_fix_lon", "moon_fix_rms_deg", "moon_fix_err_km",
    "two_body_obs", "two_body_span_min", "two_body_fix_lat", "two_body_fix_lon",
    "two_body_rms_deg", "two_body_err_km",
    "mirror_pairs", "mirror_alt_deg", "mirror_residual_deg",
    "summary",
]

# 每个 sheet 显示的列（按类型裁剪，便于阅读）
SHEETS = {
    "汇总": [
        "session_id", "created_local", "camera_id", "frames", "exposures_ms", "iso_list",
        "note", "status", "gnss_lat", "gnss_lon", "gnss_acc_m", "gnss_provider",
        "sun_frames", "sun_resid_mean", "sun_resid_rms",
        "moon_frames", "moon_resid_mean", "moon_resid_rms",
        "moon_fix_lat", "moon_fix_lon", "moon_fix_rms_deg", "moon_fix_err_km",
        "two_body_obs", "two_body_span_min", "two_body_fix_lat", "two_body_fix_lon",
        "two_body_rms_deg", "two_body_err_km",
        "mirror_pairs", "mirror_alt_deg", "mirror_residual_deg",
        "position_err_km", "error_class", "summary",
    ],
    "太阳": [
        "session_id", "created_local", "frames", "exposures_ms", "sun_frames",
        "sun_resid_mean", "sun_resid_rms", "gnss_lat", "gnss_lon", "gnss_acc_m", "summary",
    ],
    "月亮": [
        "session_id", "created_local", "frames", "exposures_ms", "moon_frames",
        "moon_resid_mean", "moon_resid_rms",
        "moon_fix_lat", "moon_fix_lon", "moon_fix_rms_deg", "moon_fix_err_km",
        "gnss_lat", "gnss_lon", "gnss_acc_m", "summary",
    ],
    "两体": [
        "session_id", "created_local", "frames", "two_body_obs", "two_body_span_min",
        "two_body_fix_lat", "two_body_fix_lon", "two_body_rms_deg", "two_body_err_km",
        "gnss_lat", "gnss_lon", "summary",
    ],
    "水镜": [
        "session_id", "created_local", "frames", "mirror_pairs", "mirror_alt_deg", "mirror_residual_deg", "summary",
    ],
    "逐帧": [
        "session_id", "frame", "exposure_ms", "iso", "detections", "matched", "rms_arcsec",
        "frame_solved", "note",
    ],
    "不可解": [
        "session_id", "created_local", "camera_id", "frames", "exposures_ms", "iso_list",
        "note", "status", "gnss_lat", "gnss_lon", "gnss_acc_m", "position_err_km", "error_class", "summary",
    ],
}

NUMERIC = {
    "frames", "gnss_lat", "gnss_lon", "gnss_acc_m", "frame", "exposure_ms", "iso",
    "detections", "matched", "rms_arcsec", "sun_frames", "sun_resid_mean", "sun_resid_rms",
    "moon_frames", "moon_resid_mean", "moon_resid_rms", "moon_fix_lat", "moon_fix_lon",
    "moon_fix_rms_deg", "moon_fix_err_km", "two_body_obs", "two_body_span_min",
    "two_body_fix_lat", "two_body_fix_lon", "two_body_rms_deg", "two_body_err_km",
    "mirror_pairs", "mirror_alt_deg", "mirror_residual_deg", "position_err_km",
}


def main():
    if not os.path.isfile(CSV_PATH):
        print("CSV 不存在：", CSV_PATH)
        sys.exit(1)
    rows = []
    with open(CSV_PATH, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append({k: (r.get(k) or "").strip() for k in HEADER})
    import re
    for r in rows:
        s = r.get("summary", "")
        m = re.search(r"与\s*GNSS\s*真值差\s*([0-9.]+)\s*km", s) or re.search(r"与真值差\s*([0-9.]+)\s*km", s) or re.search(r"中位偏差\s*([0-9.]+)\s*km", s)
        r["position_err_km"] = m.group(1) if m else ""
        e = None
        try:
            e = float(r["position_err_km"]) if r["position_err_km"] else None
        except ValueError:
            e = None
        if e is None:
            r["error_class"] = "无解/无定位结果" if r.get("status", "").startswith("不可解") else ""
        elif e <= 50.0:
            r["error_class"] = "T1 合格（≤50 km）"
        elif e <= 200.0:
            r["error_class"] = "偏差大（50–200 km）"
        else:
            r["error_class"] = "超 T1（>200 km）"
    print("总行数", len(rows))
    wb = Workbook()
    wb.remove(wb.active)
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill("solid", fgColor="2F5597")
    wrap = Alignment(wrap_text=True, vertical="top")
    for name, cols in SHEETS.items():
        ws = wb.create_sheet(name)
        ws.append(cols)
        for c in ws[1]:
            c.font = header_font
            c.fill = header_fill
            c.alignment = Alignment(vertical="center", wrap_text=True)
        ws.freeze_panes = "A2"
        n = 0
        for r in rows:
            if name == "汇总" and r["type"] != "session":
                continue
            if name == "太阳" and r["type"] != "sun":
                continue
            if name == "月亮" and r["type"] != "moon":
                continue
            if name == "两体" and r["type"] != "twobody":
                continue
            if name == "水镜" and r["type"] != "mirror":
                continue
            if name == "逐帧" and r["type"] != "frame":
                continue
            if name == "不可解":
                if r["type"] != "session" or not r["status"].startswith("不可解"):
                    continue
            out = []
            for col in cols:
                v = r.get(col, "")
                if col in NUMERIC:
                    try:
                        v = int(v) if v and float(v).is_integer() else (float(v) if v else None)
                    except ValueError:
                        v = None
                elif col == "frame_solved":
                    v = (v == "true")
                out.append(v if v != "" else None)
            ws.append(out)
            n += 1
        # 列宽
        for idx, col in enumerate(cols, start=1):
            width = 14
            if col in ("session_id",):
                width = 24
            elif col in ("created_local", "exposures_ms", "iso_list", "note"):
                width = 18
            elif col in ("summary",):
                width = 80
            elif col in ("status",):
                width = 30
            ws.column_dimensions[get_column_letter(idx)].width = width
        if ws.max_row > 1:
            ws.auto_filter.ref = f"A1:{get_column_letter(len(cols))}{ws.max_row}"
        for row in ws.iter_rows(min_row=2):
            for cell in row:
                cell.alignment = wrap
        if n == 0:
            if name == "两体":
                ws.append(["（本批数据无同帧两体结果：Analyzer 只做同帧配对，跨曝光配对在诊断测试里）"])
            elif name == "水镜":
                ws.append(["（本批数据没有水镜数据：需要直射+反射同框的采集包）"])
            else:
                ws.append(["（无数据）"])
        print(f"sheet {name}: {n} 行")
    ws = wb.create_sheet("说明", 0)
    lines = [
        ("字段", "说明"),
        ("type", "行类型：session=每包汇总；frame=逐帧；sun/moon=天体识别；twobody=两体定位；mirror=水镜像配对"),
        ("status", "可解 / 不可解（原因）。不可解原因直接取自当前分析方法的判定与提示"),
        ("position_err_km", "从分析结果里提取的定位误差（多帧融合/中位偏差），无解则空"),
        ("sun_* / moon_*", "天体识别帧数、高度残差（对 GNSS 真值的均值/RMS，度）"),
        ("moon_fix_*", "月亮轨道/单天体解算结果（lat/lon/rms/与真值差 km）"),
        ("two_body_*", "两体姿态定位：已并入 Analyzer（HDR 短帧月亮 + 长帧行星跨曝光配对）；单包内跨度只有几秒→几何不足属正常，跨会话合并解见 TwoBodyMultiSessionTest（11 组一起解误差约 1883 km）"),
        ("mirror_*", "水镜像配对：需要同时拍到直射+反射像；当前数据里没有水镜数据，故为空"),
        ("CSV 编码", "full_report.csv 为 UTF-8 with BOM（EF BB BF），本地 Excel 直接双击打开不会乱码；xlsx 本身无编码问题"),
        ("方法", "全部用当前在用的方法：FieldAnalyzer.analyze（内参/外参/零偏按当前配置）+ 逐帧星解 + MirrorPair"),
        ("生成时间", "2026-11-23 凌晨，全量 103 个采集包"),
    ]
    for i, (a, b) in enumerate(lines):
        ws.append([a, b])
        if i == 0:
            ws["A1"].font = header_font
            ws["B1"].font = header_font
            ws["A1"].fill = header_fill
            ws["B1"].fill = header_fill
    ws.column_dimensions["A"].width = 18
    ws.column_dimensions["B"].width = 100
    for row in ws.iter_rows():
        for c in row:
            c.alignment = wrap
    wb.save(OUT)
    print("已写出：", OUT)


if __name__ == "__main__":
    main()
