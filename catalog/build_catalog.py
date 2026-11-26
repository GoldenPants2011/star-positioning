# SPDX-License-Identifier: GPL-3.0-or-later

"""Build the bundled bright-star catalog asset from the HYG database (v41).

Output: app/src/main/assets/stars.bin
Layout (little endian):
  magic  "SPC1"          4 bytes
  version u32            1            (1)
  count   u32            1
  records mask           0x3F        (star count records)
  per record (6 x f32, 24 bytes):
    ra_rad, dec_rad (ICRS/J2000), vmag, pmra_mas_per_yr, pmdec_mas_per_yr, hr_id
"""
import csv, io, math, ssl, struct, sys, urllib.request, os

URL = "https://raw.githubusercontent.com/astronexus/HYG-Database/main/hyg/CURRENT/hygdata_v41.csv"
MAG_LIMIT = float(sys.argv[2]) if len(sys.argv) > 2 else 7.0
OUT = sys.argv[1] if len(sys.argv) > 1 else "stars.bin"
CACHE = os.path.join(os.path.dirname(os.path.abspath(OUT)), "hygdata_v41.csv")


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
        return dest
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-starpos/1.0"})
    with urllib.request.urlopen(req, timeout=120, context=ctx) as r, open(dest, "wb") as f:
        total = 0
        while True:
            buf = r.read(1 << 20)
            if not buf:
                break
            f.write(buf)
            total += len(buf)
    print(f"downloaded {total/1048576:.1f} MB -> {dest}")
    return dest


def main():
    path = fetch(URL, CACHE)
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for rec in csv.DictReader(f):
            try:
                mag = float(rec["mag"])
            except (TypeError, ValueError):
                continue
            if mag > MAG_LIMIT or not rec.get("ra") or not rec.get("dec"):
                continue
            ra = float(rec["ra"]) * 15.0 * math.pi / 180.0   # hours -> rad
            dec = float(rec["dec"]) * math.pi / 180.0
            def fnum(k):
                v = rec.get(k) or ""
                try:
                    return float(v)
                except ValueError:
                    return 0.0
            rows.append((ra, dec, mag, fnum("pmra"), fnum("pmdec"), fnum("hr")))
    rows.sort(key=lambda r: r[2])
    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(b"SPC1")
        f.write(struct.pack("<II", 1, len(rows)))
        for r in rows:
            f.write(struct.pack("<6f", *r))
    print(f"{len(rows)} stars (mag <= {MAG_LIMIT}) -> {OUT} ({os.path.getsize(OUT)/1024:.0f} KB)")


if __name__ == "__main__":
    main()

