-- R__seed_mortality_rates: SSA 2021 Period Life Table qx (probability of death within one year,
-- given alive at exact age) by sex and age, ages 40-119, plus a forced terminal row per sex at
-- age 120 (qx = 1.0) so the stochastic-mortality sampler always terminates.
--
-- Source: Social Security Administration, Office of the Chief Actuary, "Period Life Table, 2021,
-- as used in the 2024 Trustees Report" (Actuarial Study; table 4.C6), retrieved via the Internet
-- Archive Wayback Machine on 2026-07-16 because the live SSA pages
-- (https://www.ssa.gov/oact/STATS/table4c6.html and
-- https://www.ssa.gov/oact/STATS/table4c6_2021_TR2024.html) return HTTP 403 to automated fetches
-- (Akamai WAF). Two independent captures were cross-checked byte-for-byte on every (age, sex) pair
-- for ages 40-119 and are identical:
--   - https://web.archive.org/web/20241202151040/https://www.ssa.gov/oact/STATS/table4c6.html
--     (rolling "current" table URL, captured while it still showed the 2021/2024-Trustees-Report
--     vintage -- SSA replaces this page's content in place as newer Trustees Reports are published)
--   - https://web.archive.org/web/20260514044906/https://www.ssa.gov/oact/STATS/table4c6_2021_TR2024.html
--     (SSA's permanent per-vintage URL for the same 2021 table)
--
-- age=120/qx=1.0 is NOT an SSA figure -- SSA's table stops at age 119 (qx=0.906532, both sexes
-- converge above ~age 113). The terminal row is this project's modeling choice to force a hard
-- upper bound on sampled death age in the Monte Carlo engine.
--
-- Re-runnable: idempotent upsert, safe to re-apply on checksum change.
INSERT INTO mortality_rates (sex, age, qx) VALUES
  ('male', 40, 0.003780), ('female', 40, 0.002066),
  ('male', 41, 0.003958), ('female', 41, 0.002202),
  ('male', 42, 0.004144), ('female', 42, 0.002351),
  ('male', 43, 0.004337), ('female', 43, 0.002482),
  ('male', 44, 0.004540), ('female', 44, 0.002622),
  ('male', 45, 0.004774), ('female', 45, 0.002789),
  ('male', 46, 0.005064), ('female', 46, 0.002994),
  ('male', 47, 0.005399), ('female', 47, 0.003219),
  ('male', 48, 0.005796), ('female', 48, 0.003467),
  ('male', 49, 0.006214), ('female', 49, 0.003729),
  ('male', 50, 0.006671), ('female', 50, 0.004011),
  ('male', 51, 0.007167), ('female', 51, 0.004306),
  ('male', 52, 0.007736), ('female', 52, 0.004634),
  ('male', 53, 0.008351), ('female', 53, 0.004981),
  ('male', 54, 0.009035), ('female', 54, 0.005370),
  ('male', 55, 0.009770), ('female', 55, 0.005831),
  ('male', 56, 0.010567), ('female', 56, 0.006326),
  ('male', 57, 0.011398), ('female', 57, 0.006837),
  ('male', 58, 0.012291), ('female', 58, 0.007399),
  ('male', 59, 0.013224), ('female', 59, 0.008033),
  ('male', 60, 0.014267), ('female', 60, 0.008687),
  ('male', 61, 0.015353), ('female', 61, 0.009411),
  ('male', 62, 0.016484), ('female', 62, 0.010139),
  ('male', 63, 0.017617), ('female', 63, 0.010849),
  ('male', 64, 0.018759), ('female', 64, 0.011550),
  ('male', 65, 0.019914), ('female', 65, 0.012216),
  ('male', 66, 0.021104), ('female', 66, 0.012952),
  ('male', 67, 0.022423), ('female', 67, 0.013844),
  ('male', 68, 0.023847), ('female', 68, 0.014863),
  ('male', 69, 0.025357), ('female', 69, 0.016028),
  ('male', 70, 0.027050), ('female', 70, 0.017329),
  ('male', 71, 0.028970), ('female', 71, 0.018859),
  ('male', 72, 0.031188), ('female', 72, 0.020609),
  ('male', 73, 0.033754), ('female', 73, 0.022620),
  ('male', 74, 0.036747), ('female', 74, 0.024958),
  ('male', 75, 0.040563), ('female', 75, 0.027906),
  ('male', 76, 0.044308), ('female', 76, 0.030925),
  ('male', 77, 0.048498), ('female', 77, 0.034140),
  ('male', 78, 0.053229), ('female', 78, 0.037620),
  ('male', 79, 0.058778), ('female', 79, 0.041725),
  ('male', 80, 0.064617), ('female', 80, 0.046324),
  ('male', 81, 0.070947), ('female', 81, 0.051334),
  ('male', 82, 0.077834), ('female', 82, 0.056911),
  ('male', 83, 0.085686), ('female', 83, 0.063279),
  ('male', 84, 0.094809), ('female', 84, 0.070704),
  ('male', 85, 0.105090), ('female', 85, 0.079184),
  ('male', 86, 0.116592), ('female', 86, 0.088697),
  ('male', 87, 0.129306), ('female', 87, 0.099240),
  ('male', 88, 0.142732), ('female', 88, 0.110480),
  ('male', 89, 0.157638), ('female', 89, 0.123078),
  ('male', 90, 0.174458), ('female', 90, 0.137152),
  ('male', 91, 0.193027), ('female', 91, 0.152605),
  ('male', 92, 0.212930), ('female', 92, 0.169494),
  ('male', 93, 0.232657), ('female', 93, 0.187623),
  ('male', 94, 0.251826), ('female', 94, 0.206647),
  ('male', 95, 0.270943), ('female', 95, 0.225890),
  ('male', 96, 0.289756), ('female', 96, 0.245054),
  ('male', 97, 0.307998), ('female', 97, 0.263815),
  ('male', 98, 0.325393), ('female', 98, 0.281828),
  ('male', 99, 0.341662), ('female', 99, 0.298738),
  ('male', 100, 0.358746), ('female', 100, 0.316662),
  ('male', 101, 0.376683), ('female', 101, 0.335662),
  ('male', 102, 0.395517), ('female', 102, 0.355802),
  ('male', 103, 0.415293), ('female', 103, 0.377150),
  ('male', 104, 0.436058), ('female', 104, 0.399779),
  ('male', 105, 0.457860), ('female', 105, 0.423766),
  ('male', 106, 0.480753), ('female', 106, 0.449192),
  ('male', 107, 0.504791), ('female', 107, 0.476143),
  ('male', 108, 0.530031), ('female', 108, 0.504712),
  ('male', 109, 0.556532), ('female', 109, 0.534994),
  ('male', 110, 0.584359), ('female', 110, 0.567094),
  ('male', 111, 0.613577), ('female', 111, 0.601120),
  ('male', 112, 0.644256), ('female', 112, 0.637187),
  ('male', 113, 0.676468), ('female', 113, 0.675418),
  ('male', 114, 0.710292), ('female', 114, 0.710292),
  ('male', 115, 0.745806), ('female', 115, 0.745806),
  ('male', 116, 0.783097), ('female', 116, 0.783097),
  ('male', 117, 0.822251), ('female', 117, 0.822251),
  ('male', 118, 0.863364), ('female', 118, 0.863364),
  ('male', 119, 0.906532), ('female', 119, 0.906532),
  ('male', 120, 1.00000000), ('female', 120, 1.00000000)
ON CONFLICT (sex, age) DO UPDATE SET qx = EXCLUDED.qx, updated_at = now();
