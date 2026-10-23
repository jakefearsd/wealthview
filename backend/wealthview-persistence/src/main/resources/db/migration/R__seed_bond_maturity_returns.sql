-- R__seed_bond_maturity_returns.sql
-- Real (CPI-adjusted) annual total returns for the bond tent's short, intermediate and long
-- Treasury sleeves, 1954-2025 (72 years x 3 maturities = 216 rows). GENERATED -- do not hand-edit.
-- Regenerate the whole file: python3 -I scripts/data/derive_bond_maturity_returns.py \
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
-- Sources (FRED, retrieved 2026-10-22), https://fred.stlouisfed.org/graph/fredgraph.csv?id=<ID>:
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
-- Yield basis: 1954-01 through 1962-01 are priced from FRED monthly-AVERAGE yields (GS*), not
-- month-end yields, because daily series (DGS*) start 1962-01-02 and the roll needs a December 1961
-- month-end. January 1962 is therefore also monthly-average; month-end daily values (DGS*) are used
-- from 1962-02 on wherever they exist.
--
-- Source splices as printed by the script (series that priced each run of months, first..last):
--   short        (2-year): GS1+GS3 1954-01..1962-01; DGS1+DGS3 1962-02..1976-06; DGS2
--       1976-07..2025-12
--   intermediate (5-year): GS5 1954-01..1962-01; DGS5 1962-02..2025-12
--   long         (25-year): GS20 1954-01..1962-01; DGS20 1962-02..1977-02; DGS20+DGS30
--       1977-03..1986-12; DGS30 1987-01..1993-10; DGS20+DGS30 1993-11..2025-12
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
-- Guard result: 864 long-sleeve months checked, 0 unbridged discontinuities, 1 bridged (1973-01).
--
-- CPI: real = (1 + nominal) / (1 + cpi) - 1, cpi(Y) = mean(CPIAUCNS months published for Y) /
-- mean(CPIAUCNS months published for Y-1) - 1. Years averaging fewer than 12 months: 2025 (11 months)
-- (October 2025 CPI was never published, federal shutdown). For 1972-2025 this matches the CPI implied
-- by the existing asset-class seed to ~1e-6. For 1954-1971 that seed's rows imply a CPI rounded to 0.1%,
-- so its cash column (the ultrashort sleeve) and these sleeves are deflated slightly differently in
-- those years, by up to ~0.39 points (1954). The asset seed is left untouched.
--
-- Cross-check: the same method on the 10-year (GS10, M = 10), deflated, against the 'bond' rows
-- of R__seed_asset_class_returns.sql (Damodaran 10-year), 1972-2025: Pearson correlation 0.989
-- (required >= 0.98), mean absolute difference 0.0100 (required <= 0.015).
--
-- Fund spot checks (script NOMINAL output vs published fund total returns). Only the figures
-- marked with a source were verified; the others are unverified and support no claim.
--   SHY  2008: script +6.6%; published figure NOT verified (no source retrieved).
--   SHY  2022: script -4.0%; published -3.9% (chartrow.com/quote/shy/returns);
--       gap -0.1 points.
--   VGSH 2022: script -4.0%; published figure NOT verified (no source retrieved).
--   TLT  2008: script +34.6%; published figure NOT verified (no source retrieved).
--   TLT  2009: script -21.9%; published figure NOT verified (no source retrieved).
--   TLT  2013: script -14.4%; published figure NOT verified (no source retrieved).
--   TLT  2022: script -29.5%; published -31.4% (iShares; chartrow.com/quote/tlt/returns shows -31.2%);
--       gap +1.9 points.
--   The 25-year point is a proxy for TLT's ~25-year average maturity, so a gap against the fund
--   is expected.
--
-- Pre-1954: FRED constant-maturity yields start April 1953. LTGOVTBD covers long bonds from
-- 1925, but no free, credible short- or intermediate-maturity source was found before 1953.
-- A year needs every maturity, so no pre-1954 rows are seeded; design spec 4.1's fallback
-- applies (include_depression_years disabled with a tent, long-run averages for missing years,
-- start-year picker from 1954).
--
TRUNCATE TABLE bond_maturity_returns;
INSERT INTO bond_maturity_returns (year, maturity, real_return) VALUES
  (1954, 'short', 0.017375),
  (1954, 'intermediate', 0.024519),
  (1954, 'long', 0.064500),
  (1955, 'short', 0.000084),
  (1955, 'intermediate', -0.006692),
  (1955, 'long', -0.023475),
  (1956, 'short', -0.001897),
  (1956, 'intermediate', -0.018348),
  (1956, 'long', -0.062549),
  (1957, 'short', 0.014698),
  (1957, 'intermediate', 0.032449),
  (1957, 'long', 0.013663),
  (1958, 'short', -0.008389),
  (1958, 'intermediate', -0.029965),
  (1958, 'long', -0.069034),
  (1959, 'short', 0.002477),
  (1959, 'intermediate', -0.018440),
  (1959, 'long', -0.041339),
  (1960, 'short', 0.063188),
  (1960, 'intermediate', 0.089573),
  (1960, 'long', 0.095038),
  (1961, 'short', 0.016047),
  (1961, 'intermediate', 0.016014),
  (1961, 'long', 0.003138),
  (1962, 'short', 0.026122),
  (1962, 'intermediate', 0.043917),
  (1962, 'long', 0.057596),
  (1963, 'short', 0.009349),
  (1963, 'intermediate', 0.003523),
  (1963, 'long', -0.013963),
  (1964, 'short', 0.025415),
  (1964, 'intermediate', 0.025611),
  (1964, 'long', 0.026378),
  (1965, 'short', 0.008377),
  (1965, 'intermediate', -0.006774),
  (1965, 'long', -0.019786),
  (1966, 'short', 0.022744),
  (1966, 'intermediate', 0.024898),
  (1966, 'long', 0.008173),
  (1967, 'short', 0.007354),
  (1967, 'intermediate', -0.018725),
  (1967, 'long', -0.109587),
  (1968, 'short', 0.003314),
  (1968, 'intermediate', -0.008369),
  (1968, 'long', -0.042420),
  (1969, 'short', -0.017214),
  (1969, 'intermediate', -0.059888),
  (1969, 'long', -0.103230),
  (1970, 'short', 0.069960),
  (1970, 'intermediate', 0.114133),
  (1970, 'long', 0.071592),
  (1971, 'short', 0.021314),
  (1971, 'intermediate', 0.037773),
  (1971, 'long', 0.078474),
  (1972, 'short', 0.000962),
  (1972, 'intermediate', -0.004881),
  (1972, 'long', 0.022600),
  (1973, 'short', -0.010178),
  (1973, 'intermediate', -0.015699),
  (1973, 'long', -0.064879),
  (1974, 'short', -0.029334),
  (1974, 'intermediate', -0.047932),
  (1974, 'long', -0.082257),
  (1975, 'short', -0.003957),
  (1975, 'intermediate', -0.016255),
  (1975, 'long', -0.018371),
  (1976, 'short', 0.034957),
  (1976, 'intermediate', 0.074864),
  (1976, 'long', 0.125932),
  (1977, 'short', -0.032430),
  (1977, 'intermediate', -0.050530),
  (1977, 'long', -0.072848),
  (1978, 'short', -0.037979),
  (1978, 'intermediate', -0.059341),
  (1978, 'long', -0.084814),
  (1979, 'short', -0.027713),
  (1979, 'intermediate', -0.052520),
  (1979, 'long', -0.116883),
  (1980, 'short', -0.040518),
  (1980, 'intermediate', -0.092908),
  (1980, 'long', -0.161135),
  (1981, 'short', 0.036029),
  (1981, 'intermediate', -0.007755),
  (1981, 'long', -0.092837),
  (1982, 'short', 0.147639),
  (1982, 'intermediate', 0.233467),
  (1982, 'long', 0.384678),
  (1983, 'short', 0.047332),
  (1983, 'intermediate', 0.020211),
  (1983, 'long', -0.035777),
  (1984, 'short', 0.092480),
  (1984, 'intermediate', 0.102495),
  (1984, 'long', 0.111475),
  (1985, 'short', 0.097853),
  (1985, 'intermediate', 0.179076),
  (1985, 'long', 0.307583),
  (1986, 'short', 0.081723),
  (1986, 'intermediate', 0.128571),
  (1986, 'long', 0.293079),
  (1987, 'short', 0.011835),
  (1987, 'intermediate', -0.019498),
  (1987, 'long', -0.100487),
  (1988, 'short', 0.016282),
  (1988, 'intermediate', 0.011767),
  (1988, 'long', 0.043993),
  (1989, 'short', 0.062540),
  (1989, 'intermediate', 0.091734),
  (1989, 'long', 0.151382),
  (1990, 'short', 0.042367),
  (1990, 'intermediate', 0.038454),
  (1990, 'long', 0.002457),
  (1991, 'short', 0.068564),
  (1991, 'intermediate', 0.109606),
  (1991, 'long', 0.140152),
  (1992, 'short', 0.022166),
  (1992, 'intermediate', 0.027420),
  (1992, 'long', 0.048371),
  (1993, 'short', 0.017277),
  (1993, 'intermediate', 0.059210),
  (1993, 'long', 0.176351),
  (1994, 'short', -0.029037),
  (1994, 'intermediate', -0.066815),
  (1994, 'long', -0.122244),
  (1995, 'short', 0.082469),
  (1995, 'intermediate', 0.147609),
  (1995, 'long', 0.311851),
  (1996, 'short', 0.016682),
  (1996, 'intermediate', -0.002925),
  (1996, 'long', -0.049169),
  (1997, 'short', 0.041341),
  (1997, 'intermediate', 0.061542),
  (1997, 'long', 0.139730),
  (1998, 'short', 0.057899),
  (1998, 'intermediate', 0.089027),
  (1998, 'long', 0.147722),
  (1999, 'short', 0.001149),
  (1999, 'intermediate', -0.042892),
  (1999, 'long', -0.135081),
  (2000, 'short', 0.051029),
  (2000, 'intermediate', 0.090058),
  (2000, 'long', 0.185702),
  (2001, 'short', 0.049127),
  (2001, 'intermediate', 0.044640),
  (2001, 'long', 0.014308),
  (2002, 'short', 0.038850),
  (2002, 'intermediate', 0.097907),
  (2002, 'long', 0.139221),
  (2003, 'short', -0.010362),
  (2003, 'intermediate', -0.015180),
  (2003, 'long', -0.009046),
  (2004, 'short', -0.026038),
  (2004, 'intermediate', -0.009839),
  (2004, 'long', 0.068962),
  (2005, 'short', -0.019619),
  (2005, 'intermediate', -0.025174),
  (2005, 'long', 0.055109),
  (2006, 'short', 0.008541),
  (2006, 'intermediate', -0.000062),
  (2006, 'long', -0.027265),
  (2007, 'short', 0.049349),
  (2007, 'intermediate', 0.073547),
  (2007, 'long', 0.078415),
  (2008, 'short', 0.026580),
  (2008, 'intermediate', 0.080947),
  (2008, 'long', 0.296490),
  (2009, 'short', 0.005573),
  (2009, 'intermediate', -0.028178),
  (2009, 'long', -0.215751),
  (2010, 'short', 0.000722),
  (2010, 'intermediate', 0.034387),
  (2010, 'long', 0.079983),
  (2011, 'short', -0.019454),
  (2011, 'intermediate', 0.041037),
  (2011, 'long', 0.285878),
  (2012, 'short', -0.017660),
  (2012, 'intermediate', -0.007928),
  (2012, 'long', 0.000891),
  (2013, 'short', -0.013994),
  (2013, 'intermediate', -0.051299),
  (2013, 'long', -0.156253),
  (2014, 'short', -0.017048),
  (2014, 'intermediate', 0.004804),
  (2014, 'long', 0.250896),
  (2015, 'short', -0.002005),
  (2015, 'intermediate', 0.008341),
  (2015, 'long', -0.019174),
  (2016, 'short', -0.007048),
  (2016, 'intermediate', -0.007734),
  (2016, 'long', -0.007298),
  (2017, 'short', -0.020233),
  (2017, 'intermediate', -0.014668),
  (2017, 'long', 0.054962),
  (2018, 'short', -0.010348),
  (2018, 'intermediate', -0.011497),
  (2018, 'long', -0.045463),
  (2019, 'short', 0.018842),
  (2019, 'intermediate', 0.040258),
  (2019, 'long', 0.123262),
  (2020, 'short', 0.019700),
  (2020, 'intermediate', 0.058219),
  (2020, 'long', 0.160404),
  (2021, 'short', -0.053565),
  (2021, 'intermediate', -0.077986),
  (2021, 'long', -0.098382),
  (2022, 'short', -0.111088),
  (2022, 'intermediate', -0.160320),
  (2022, 'long', -0.347061),
  (2023, 'short', 0.008898),
  (2023, 'intermediate', 0.006314),
  (2023, 'long', -0.012370),
  (2024, 'short', 0.014287),
  (2024, 'intermediate', -0.012232),
  (2024, 'long', -0.088140),
  (2025, 'short', 0.026651),
  (2025, 'intermediate', 0.042347),
  (2025, 'long', 0.021496);
