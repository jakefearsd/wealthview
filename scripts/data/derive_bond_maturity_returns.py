#!/usr/bin/env python3
"""Derive real annual Treasury returns (short/intermediate/long) from FRED constant-maturity yields.

Usage: python3 -I scripts/data/derive_bond_maturity_returns.py [--data-dir DIR] [--last-year YEAR]
                                                                [--output PATH]

Writes the COMPLETE R__seed_bond_maturity_returns.sql (header comment, TRUNCATE, INSERT, rows) to
--output, or to stdout without it, and a summary (source splices, bridge/guard result, short CPI
years, cross-check) to stderr. Exits non-zero, writing nothing, if any month cannot be priced, the
long-yield guard trips on an unbridged month, or the cross-check against the existing asset-class
'bond' seed fails.

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
import textwrap
import urllib.request

FIRST_YEAR, DEFAULT_LAST_YEAR = 1954, 2025
RETRIEVED = "2026-10-22"  # update when the FRED CSVs are re-downloaded
MATURITY_YEARS = {"short": 2, "intermediate": 5, "long": 25}
# Per month, the first (source, series) whose yields exist at BOTH the previous month-end and this
# month-end wins. Loop sources OUTERMOST: try every series in daily month-end (DGS*) before any
# series in monthly average (GS*). A tuple means "average the members" (both months, same members),
# e.g. the 2-year point as the midpoint of the 1- and 3-year CMT and the 25-year point as the midpoint
# of the 20- and 30-year CMT.
YIELD_SERIES = {"short": [("GS2",), ("GS1", "GS3")],
                "intermediate": [("GS5",)],
                "long": [("GS20", "GS30"), ("GS20",), ("GS30",)]}
# Months whose long-sleeve END yield is bridged: y1 = y0 + (GS10 y1 - GS10 y0), i.e. the long yield is
# moved by the 10-year CMT's change that month instead of by the 20-year point's own change. January 1973
# is a discontinuity in the 20-year CMT's bond composition, not a market move: DGS20 rises 83bp
# (6.03 -> 6.86) while DGS10 rises 13bp (6.41 -> 6.54), which would book a -9.3% month. This is the one
# explicit exception to "no interpolation"; every other month is priced as observed.
BRIDGE_MONTHS = {("long", "1973-01")}
GUARDED_MATURITIES = ("long",)
GUARD_REFERENCE = "GS10"
GUARD_SPREAD_MOVE, GUARD_REFERENCE_MOVE = 0.0050, 0.0020  # 50bp spread move while the 10-year moves < 20bp
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
    """First (source, members) with yields at both the previous and this month-end, else None.

    Returns ((source, series-id label), y0, y1, source prefix); yields are decimals.
    """
    prev_key = prev_month(*month_key)
    for source_prefix, label in (("D", "daily"), ("", "monthly")):
        for members in series_list:
            ids = [source_prefix + m for m in members]
            y0 = [data[i].get(prev_key) for i in ids]
            y1 = [data[i].get(month_key) for i in ids]
            if None in y0 or None in y1:
                continue
            return (label, "+".join(ids)), sum(y0) / len(y0) / 100.0, sum(y1) / len(y1) / 100.0, source_prefix
    return None


def month_name(month_key):
    return "%04d-%02d" % month_key


def reference_move(data, prefix, month_key):
    """The guard reference (10-year CMT) change in the same source as the priced pair, or None."""
    ref = data[prefix + GUARD_REFERENCE]
    r0, r1 = ref.get(prev_month(*month_key)), ref.get(month_key)
    return None if r0 is None or r1 is None else (r1 - r0) / 100.0


def price_months(name, series_list, maturity_years, data, years):
    """Return (nominal by year, [(year, month, series label)], guard stats, bridges applied).

    Guard stats is (months checked, [offending description]).
    """
    nominal, months, checked, tripped, bridged = {}, [], 0, [], []
    for year in years:
        growth = 1.0
        for month in range(1, 13):
            key = (year, month)
            pair = find_pair(series_list, data, key)
            if pair is None:
                sys.exit("ERROR: no yield pair for %d-%02d (series %s)" % (year, month, series_list))
            label, y0, y1, prefix = pair
            ref = reference_move(data, prefix, key) if name in GUARDED_MATURITIES else None
            if ref is not None:
                checked += 1
                spread_move = (y1 - y0) - ref
                if (name, month_name(key)) in BRIDGE_MONTHS:
                    pass
                elif abs(spread_move) > GUARD_SPREAD_MOVE and abs(ref) < GUARD_REFERENCE_MOVE:
                    tripped.append("%s %s: %s moved %+.0fbp while %s moved %+.0fbp (spread move %+.0fbp)"
                                   % (name, month_name(key), label[1], (y1 - y0) * 1e4,
                                      GUARD_REFERENCE, ref * 1e4, spread_move * 1e4))
            if (name, month_name(key)) in BRIDGE_MONTHS:
                if ref is None:
                    sys.exit("ERROR: cannot bridge %s %s: %s missing" % (name, month_name(key), GUARD_REFERENCE))
                bridged.append("%s %s: %s %.2f%% -> %.2f%% bridged to %.2f%% (%s %+.0fbp)" % (
                    name, month_name(key), label[1], y0 * 100, y1 * 100, (y0 + ref) * 100,
                    GUARD_REFERENCE, ref * 1e4))
                y1 = y0 + ref
            months.append((year, month, label))
            growth *= 1 + par_price(y0, maturity_years - 1 / 12, y1) - 1 + y0 / 12
        nominal[year] = growth - 1
    if tripped:
        sys.exit("ERROR: unbridged yield discontinuity (add to BRIDGE_MONTHS only after review):\n  "
                 + "\n  ".join(tripped))
    return nominal, months, checked, bridged


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


def month_runs(months):
    """Compress [(year, month, label)] into 'label first..last' runs naming exact months."""
    runs = []
    for year, month, label in months:
        if runs and runs[-1][2] == label:
            runs[-1][1] = (year, month)
        else:
            runs.append([(year, month), (year, month), label])
    return ["%s %s..%s" % (label[1], month_name(a), month_name(b)) for a, b, label in runs]


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


# Published fund total returns cited in the header: (label, maturity, year, source text or None if unverified).
FUND_CHECKS = [("SHY ", "short", 2008, None, None), ("SHY ", "short", 2022, -3.9, "chartrow.com/quote/shy/returns"),
               ("VGSH", "short", 2022, None, None), ("TLT ", "long", 2008, None, None),
               ("TLT ", "long", 2009, None, None), ("TLT ", "long", 2013, None, None),
               ("TLT ", "long", 2022, -31.4, "iShares; chartrow.com/quote/tlt/returns shows -31.2%")]


def fund_table(nominals):
    lines = []
    for fund, maturity, year, published, source in FUND_CHECKS:
        script = nominals[maturity][year] * 100
        if source is None:
            tail = "published figure NOT verified (no source retrieved)."
        else:
            tail = "published %+.1f%% (%s);\n--       gap %+.1f points." % (published, source, script - published)
        lines.append("--   %s %d: script %+.1f%%; %s" % (fund, year, script, tail))
    return lines


def build_header(ctx):
    first, last = ctx["first"], ctx["last"]
    n = last - first + 1
    splice = []
    for maturity, mat_years in MATURITY_YEARS.items():
        runs = month_runs(ctx["months"][maturity])
        splice.append(textwrap.fill("%-12s (%d-year): %s" % (maturity, mat_years, "; ".join(runs)), 100,
                                    initial_indent="--   ", subsequent_indent="--       "))
    cpi_short = ", ".join("%d (%d months)" % kv for kv in sorted(ctx["short_cpi"].items())) or "none"
    lines = """-- R__seed_bond_maturity_returns.sql
-- Real (CPI-adjusted) annual total returns for the bond tent's short, intermediate and long
-- Treasury sleeves, {first}-{last} ({n} years x 3 maturities = {rows} rows). GENERATED -- do not hand-edit.
-- Regenerate the whole file: python3 -I scripts/data/derive_bond_maturity_returns.py \\
--     --output backend/wealthview-persistence/src/main/resources/db/migration/R__seed_bond_maturity_returns.sql
--     (add --data-dir DIR to reuse downloaded CSVs, --last-year YYYY to extend the series)
--
-- Refresh checklist:
--   * Refresh R__seed_asset_class_returns.sql and this seed TOGETHER.
--     BondMaturityReturnsProvider.alignedTo fails loudly if the asset matrix has a year this seed
--     lacks, which would break every bond-tent scenario's Monte Carlo.
--   * Re-run the script with --last-year set to the new final year; it stops on a missing cross-check year.
--   * Update the year constants in BondMaturityReturnsSeedIntegrationTest and docs/reference/data-model.md.
--   * CPI averages only the months FRED has published for a year (see CPI below).
--
-- Sources (FRED, retrieved {retrieved}), https://fred.stlouisfed.org/graph/fredgraph.csv?id=<ID>:
--   Monthly-average constant-maturity yields: GS1 GS2 GS3 GS5 GS10 GS20 GS30
--   Daily constant-maturity yields:           DGS1 DGS2 DGS3 DGS5 DGS10 DGS20 DGS30
--   CPI-U, not seasonally adjusted:           CPIAUCNS
--   A blank or '.' observation is missing. A daily series' month-end value is its last
--   non-missing observation in the month.
--
-- Maturities (and the funds they stand in for):
--   short        = 2-year  (VGSH / SHY, 1-3 year Treasuries)
--   intermediate = 5-year  (VGIT, 3-10 year Treasuries, about 5-year duration)
--   long         = 25-year (TLT, 20+ year Treasuries, average about 25 years), priced as the midpoint
--                  of the 20- and 30-year CMT where both exist, else the 20-year, else the 30-year.
--   ultrashort is NOT stored: it is asset_class_returns.cash (the schema CHECK rejects it).
--
-- Method: monthly constant-maturity roll. Each month, buy a new par bond (coupon = previous
-- month-end yield y0, semiannual) at maturity M, hold one month, sell at this month-end yield y1
-- with M - 1/12 years left, plus one month of coupon:
--   r_month = par_price(y0, M - 1/12, y1) - 1 + y0/12
-- nominal(Y) = product of the twelve monthly (1 + r_month) - 1.
-- Per month the first (source, series) with yields at BOTH month-ends wins; sources are tried
-- daily (DGS*) before monthly average (GS*). A tuple averages its members (2-year = midpoint of
-- the 1- and 3-year CMT before GS2/DGS2 exist). A month with no pair stops the script.
--
-- Yield basis: {first}-01 through 1962-01 are priced from FRED monthly-AVERAGE yields (GS*), not
-- month-end yields, because daily series (DGS*) start 1962-01-02 and the roll needs a December 1961
-- month-end. January 1962 is therefore also monthly-average; month-end daily values (DGS*) are used
-- from 1962-02 on wherever they exist.
--
-- Source splices as printed by the script (series that priced each run of months, first..last):
{splice}
--
-- January 1973 bridge (BRIDGE_MONTHS = long 1973-01): the 20-year CMT jumps +83bp in that month
-- (DGS20 6.03 -> 6.86) while the 10-year moves +13bp (6.41 -> 6.54). That is a change in the 20-year
-- point's bond composition, not a market move; priced as observed it books a -9.3% month and a 1973
-- long real return of -14.25%, against about -1.1% nominal / -6.9% real for SBBI long government bonds.
-- (The LTGOVTBD long-bond yield also rose only 31bp that month.) In the bridged month the long end
-- yield is y0 + the 10-year CMT's change that month, from the same daily/monthly source. Every other
-- month is priced as observed. A guard stops the script if any other month has the long-minus-10-year spread
-- move by more than 50bp while the 10-year itself moves less than 20bp; an unbridged discontinuity
-- must be reviewed and added to BRIDGE_MONTHS explicitly.
-- Guard result: {guard}.
--
-- CPI: real = (1 + nominal) / (1 + cpi) - 1, cpi(Y) = mean(CPIAUCNS months published for Y) /
-- mean(CPIAUCNS months published for Y-1) - 1. Years averaging fewer than 12 months: {cpi_short}
-- (October 2025 CPI was never published, federal shutdown). For 1972-{last} this matches the CPI implied
-- by the existing asset-class seed to ~1e-6. For 1954-1971 that seed's rows imply a CPI rounded to 0.1%,
-- so its cash column (the ultrashort sleeve) and these sleeves are deflated slightly differently in
-- those years, by up to ~0.39 points (1954). The asset seed is left untouched.
--
-- Cross-check: the same method on the 10-year (GS10, M = 10), deflated, against the 'bond' rows
-- of R__seed_asset_class_returns.sql (Damodaran 10-year), 1972-{last}: Pearson correlation {corr:.3f}
-- (required >= {mincorr}), mean absolute difference {mad:.4f} (required <= {maxmad}).
--
-- Fund spot checks (script NOMINAL output vs published fund total returns). Only the figures
-- marked with a source were verified; the others are unverified and support no claim.
{funds}
--   The 25-year point is a proxy for TLT's ~25-year average maturity, so a gap against the fund
--   is expected.
--
-- Pre-1954: FRED constant-maturity yields start April 1953. LTGOVTBD covers long bonds from
-- 1925, but no free, credible short- or intermediate-maturity source was found before 1953.
-- A year needs every maturity, so no pre-1954 rows are seeded; design spec 4.1's fallback
-- applies (include_depression_years disabled with a tent, long-run averages for missing years,
-- start-year picker from 1954).
--
""".format(first=first, last=last, n=n, rows=n * 3, retrieved=RETRIEVED, splice="\n".join(splice),
           guard=ctx["guard"], cpi_short=cpi_short, corr=ctx["corr"], mincorr=MIN_CORRELATION,
           mad=ctx["mad"], maxmad=MAX_MEAN_ABS_DIFF, funds="\n".join(fund_table(ctx["nominals"])))
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data-dir", help="directory of FRED CSVs (<ID>.csv); downloaded there if absent")
    parser.add_argument("--last-year", type=int, default=DEFAULT_LAST_YEAR, help="final year to seed")
    parser.add_argument("--output", help="write the seed SQL here (default: stdout)")
    args = parser.parse_args()
    last_year = args.last_year

    data = {sid: load_series(sid, args.data_dir) for sid in SERIES_IDS}
    years = list(range(FIRST_YEAR, last_year + 1))
    inflation, short_cpi = cpi_inflation(data["CPIAUCNS"], years)

    real, nominals, months, checked, bridges = {}, {}, {}, 0, []
    for maturity, mat_years in MATURITY_YEARS.items():
        nominal, months[maturity], n_checked, bridged = price_months(
            maturity, YIELD_SERIES[maturity], mat_years, data, years)
        nominals[maturity] = nominal
        real[maturity] = to_real(nominal, inflation)
        checked += n_checked
        bridges += bridged

    # Cross-check against the existing 10-year bond seed
    xyears = list(range(1972, last_year + 1))
    seed = seed_bond_returns()
    missing = [y for y in xyears if y not in seed]
    if missing:
        sys.exit("ERROR: R__seed_asset_class_returns.sql has no 'bond' row for year(s) %s; refresh it first"
                 % ", ".join(map(str, missing)))
    xnominal, _, _, _ = price_months("cross-check", CROSS_CHECK_SERIES, CROSS_CHECK_MATURITY, data, xyears)
    xreal = to_real(xnominal, cpi_inflation(data["CPIAUCNS"], xyears)[0])
    ours = [xreal[y] for y in xyears]
    theirs = [seed[y] for y in xyears]
    corr = pearson(ours, theirs)
    mad = sum(abs(a - b) for a, b in zip(ours, theirs)) / len(ours)
    ok = corr >= MIN_CORRELATION and mad <= MAX_MEAN_ABS_DIFF

    guard = "%d long-sleeve months checked, 0 unbridged discontinuities, %d bridged (%s)" % (
        checked, len(bridges), ", ".join(sorted(m for _, m in BRIDGE_MONTHS)))
    ctx = {"first": FIRST_YEAR, "last": last_year, "months": months, "short_cpi": short_cpi, "guard": guard,
           "corr": corr, "mad": mad, "nominals": nominals}
    rows = []
    for y in years:
        for maturity in MATURITY_YEARS:
            last = y == last_year and maturity == "long"
            rows.append("  (%d, '%s', %s)%s" % (y, maturity, format(real[maturity][y], ".6f"), ";" if last else ","))
    text = (build_header(ctx) + "TRUNCATE TABLE bond_maturity_returns;\n"
            "INSERT INTO bond_maturity_returns (year, maturity, real_return) VALUES\n" + "\n".join(rows) + "\n")

    # Summary
    for maturity in MATURITY_YEARS:
        print("splice %-12s (%d-year): %s" % (maturity, MATURITY_YEARS[maturity],
              "; ".join(month_runs(months[maturity]))), file=sys.stderr)
    print("guard: %s" % guard, file=sys.stderr)
    for b in bridges:
        print("bridge: %s" % b, file=sys.stderr)
    print("CPI years averaging fewer than 12 months: %s" %
          (", ".join("%d (%d months)" % kv for kv in sorted(short_cpi.items())) or "none"), file=sys.stderr)
    print("cross-check vs asset_class_returns bond 1972-%d: correlation %.3f (min %.2f), "
          "mean abs diff %.4f (max %.3f) -> %s" % (last_year, corr, MIN_CORRELATION, mad, MAX_MEAN_ABS_DIFF,
                                                     "PASS" if ok else "FAIL"), file=sys.stderr)
    for line in fund_table(nominals):
        print(line[3:], file=sys.stderr)
    if not ok:
        sys.exit(1)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
