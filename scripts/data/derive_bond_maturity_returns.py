#!/usr/bin/env python3
"""Derive real annual Treasury returns (short/intermediate/long) from FRED constant-maturity yields.

Usage: python3 -I scripts/data/derive_bond_maturity_returns.py [--data-dir DIR]

Writes the SQL VALUES rows for R__seed_bond_maturity_returns.sql to stdout and a summary
(source splices, short CPI years, cross-check) to stderr. Exits non-zero if any month cannot be
priced or the cross-check against the existing asset-class 'bond' seed fails.

Method: a monthly constant-maturity roll. Each month, buy a new par bond at the previous month-end
yield, hold one month, sell at this month-end yield with 1/12 year less to maturity, plus one month
of coupon. Nominal annual return compounds the twelve months; real = (1+nominal)/(1+cpi) - 1.
"""
import argparse
import csv
import math
import os
import re
import sys
import urllib.request

FIRST_YEAR, LAST_YEAR = 1954, 2025
MATURITY_YEARS = {"short": 2, "intermediate": 5, "long": 25}
# Per month, the first (source, series) whose yields exist at BOTH the previous month-end and this
# month-end wins. Loop sources OUTERMOST: try every series in daily month-end (DGS*) before any
# series in monthly average (GS*). A tuple means "average the members" (both months, same members),
# e.g. the 2-year point as the midpoint of the 1- and 3-year CMT.
YIELD_SERIES = {"short": [("GS2",), ("GS1", "GS3")],
                "intermediate": [("GS5",)],
                "long": [("GS20",), ("GS30",)]}
CROSS_CHECK_SERIES = [("GS10",)]
CROSS_CHECK_MATURITY = 10
SERIES_IDS = ["GS1", "GS2", "GS3", "GS5", "GS10", "GS20", "GS30",
              "DGS1", "DGS2", "DGS3", "DGS5", "DGS10", "DGS20", "DGS30", "CPIAUCNS"]
FRED_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id={}"
MIN_CORRELATION, MAX_MEAN_ABS_DIFF = 0.98, 0.015
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
ASSET_SEED = os.path.join(REPO_ROOT, "backend", "wealthview-persistence", "src", "main", "resources",
                          "db", "migration", "R__seed_asset_class_returns.sql")


def load_series(series_id, data_dir):
    """Return {(year, month): value} for the series, daily series reduced to last non-missing per month."""
    path = os.path.join(data_dir, series_id + ".csv") if data_dir else None
    if path and os.path.exists(path):
        with open(path, newline="", encoding="utf-8") as f:
            text = f.read()
    else:
        with urllib.request.urlopen(FRED_URL.format(series_id), timeout=60) as resp:
            text = resp.read().decode("utf-8")
        if data_dir:
            os.makedirs(data_dir, exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
    out = {}
    rows = csv.reader(text.splitlines())
    next(rows)
    for date, value in rows:
        if value.strip() in ("", "."):
            continue
        year, month = int(date[0:4]), int(date[5:7])
        out[(year, month)] = float(value)  # file is chronological: the last one in a month wins
    return out


def par_price(coupon, years, y):
    if y == 0:
        return coupon * years + 1.0
    v = (1 + y / 2) ** (-2 * years)
    return coupon / y * (1 - v) + v


def prev_month(year, month):
    return (year - 1, 12) if month == 1 else (year, month - 1)


def find_pair(series_list, data, month_key):
    """First (source, members) with yields at both the previous and this month-end, else None."""
    prev_key = prev_month(*month_key)
    for source_prefix, label in (("D", "daily"), ("", "monthly")):
        for members in series_list:
            ids = [source_prefix + m for m in members]
            y0 = [data[i].get(prev_key) for i in ids]
            y1 = [data[i].get(month_key) for i in ids]
            if None in y0 or None in y1:
                continue
            return (label, "+".join(ids)), sum(y0) / len(y0) / 100.0, sum(y1) / len(y1) / 100.0
    return None


def nominal_returns(series_list, maturity_years, data, years):
    """Return ({year: nominal}, {year: set of series ids})."""
    nominal, used = {}, {}
    for year in years:
        growth, labels = 1.0, set()
        for month in range(1, 13):
            pair = find_pair(series_list, data, (year, month))
            if pair is None:
                sys.exit("ERROR: no yield pair for %d-%02d (series %s)" % (year, month, series_list))
            label, y0, y1 = pair
            labels.add(label[1])
            growth *= 1 + par_price(y0, maturity_years - 1 / 12, y1) - 1 + y0 / 12
        nominal[year] = growth - 1
        used[year] = labels
    return nominal, used


def cpi_inflation(cpi, years):
    def avg(y):
        vals = [v for (yy, _), v in cpi.items() if yy == y]
        return sum(vals) / len(vals), len(vals)
    out, short_years = {}, {}
    for y in years:
        cur, n = avg(y)
        out[y] = cur / avg(y - 1)[0] - 1
        if n < 12:
            short_years[y] = n
    return out, short_years


def to_real(nominal, inflation):
    return {y: (1 + nominal[y]) / (1 + inflation[y]) - 1 for y in nominal}


def ranges(items):
    """Compress [(year, label)] into 'label: a-b' runs."""
    runs = []
    for year, label in items:
        if runs and runs[-1][2] == label and runs[-1][1] == year - 1:
            runs[-1][1] = year
        else:
            runs.append([year, year, label])
    return ["%s %d-%d" % (label, a, b) for a, b, label in runs]


def seed_bond_returns():
    out = {}
    with open(ASSET_SEED, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*\((\d{4}), 'bond', (-?[\d.]+)\)", line)
            if m:
                out[int(m.group(1))] = float(m.group(2))
    return out


def pearson(a, b):
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    return cov / math.sqrt(sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data-dir", help="directory of FRED CSVs (<ID>.csv); downloaded there if absent")
    args = parser.parse_args()

    data = {sid: load_series(sid, args.data_dir) for sid in SERIES_IDS}
    years = list(range(FIRST_YEAR, LAST_YEAR + 1))
    inflation, short_cpi = cpi_inflation(data["CPIAUCNS"], list(range(FIRST_YEAR, LAST_YEAR + 1)))

    real, splices = {}, {}
    for maturity, mat_years in MATURITY_YEARS.items():
        nominal, used = nominal_returns(YIELD_SERIES[maturity], mat_years, data, years)
        real[maturity] = to_real(nominal, inflation)
        splices[maturity] = used

    for y in years:
        for maturity in MATURITY_YEARS:
            last = y == LAST_YEAR and maturity == "long"
            print("  (%d, '%s', %s)%s" % (y, maturity, format(real[maturity][y], ".6f"), ";" if last else ","))

    # Summary
    for maturity in MATURITY_YEARS:
        print("splice %-12s (%d-year): %s" % (maturity, MATURITY_YEARS[maturity],
              "; ".join(ranges([(y, "/".join(sorted(splices[maturity][y]))) for y in years]))), file=sys.stderr)
    print("CPI years averaging fewer than 12 months: %s" %
          (", ".join("%d (%d months)" % kv for kv in sorted(short_cpi.items())) or "none"), file=sys.stderr)

    # Cross-check against the existing 10-year bond seed
    xyears = [y for y in range(1972, LAST_YEAR + 1)]
    xnominal, _ = nominal_returns(CROSS_CHECK_SERIES, CROSS_CHECK_MATURITY, data, xyears)
    xinfl, _ = cpi_inflation(data["CPIAUCNS"], xyears)
    xreal = to_real(xnominal, xinfl)
    seed = seed_bond_returns()
    ours = [xreal[y] for y in xyears]
    theirs = [seed[y] for y in xyears]
    corr = pearson(ours, theirs)
    mad = sum(abs(a - b) for a, b in zip(ours, theirs)) / len(ours)
    ok = corr >= MIN_CORRELATION and mad <= MAX_MEAN_ABS_DIFF
    print("cross-check vs asset_class_returns bond 1972-%d: correlation %.3f (min %.2f), "
          "mean abs diff %.4f (max %.3f) -> %s" % (LAST_YEAR, corr, MIN_CORRELATION, mad, MAX_MEAN_ABS_DIFF,
                                                     "PASS" if ok else "FAIL"), file=sys.stderr)
    # Nominal fund spot-check figures
    for maturity, yrs in (("short", (2008, 2022)), ("long", (2008, 2009, 2013, 2022))):
        nom, _ = nominal_returns(YIELD_SERIES[maturity], MATURITY_YEARS[maturity], data, list(yrs))
        print("nominal %s: %s" % (maturity, ", ".join("%d %+.1f%%" % (y, nom[y] * 100) for y in yrs)),
              file=sys.stderr)
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
