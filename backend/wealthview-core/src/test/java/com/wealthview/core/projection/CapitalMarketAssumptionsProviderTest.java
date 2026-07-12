package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import com.wealthview.persistence.repository.AssetClassReturnRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapitalMarketAssumptionsProviderTest {

    private static AssetClassReturnEntity row(int y, String c, String r) {
        return new AssetClassReturnEntity(y, c, new BigDecimal(r));
    }

    @Test
    void matrix_completeData_buildsAlignedGrid() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01"),
                row(1973, "us_stock", "-0.05"), row(1973, "intl_stock", "-0.03"),
                row(1973, "bond", "0.03"), row(1973, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        var m = provider.matrix();

        assertThat(m.years()).containsExactly(1972, 1973);
        int us = Arrays.asList(m.classes()).indexOf(AssetClass.US_STOCK);
        assertThat(m.realReturns()[0][us]).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void geometricMeans_computesPerClassCompoundMean() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01"),
                row(1973, "us_stock", "0.10"), row(1973, "intl_stock", "0.08"),
                row(1973, "bond", "0.02"), row(1973, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThat(provider.geometricMeans().get(AssetClass.US_STOCK)).isEqualTo(0.10, within(1e-9));
    }

    @Test
    void matrix_yearMissingAClass_throws() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1972, "us_stock", "0.10"), row(1972, "bond", "0.02")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThatThrownBy(provider::matrix).isInstanceOf(IllegalStateException.class);
    }

    // ===================================================================================
    // Audit C10 -- default-window predicate (DEFAULT_MIN_YEAR = 1972) and the
    // include_depression_years opt-in (matrix(true) / geometricMeans(true)).
    // ===================================================================================

    /**
     * A pre-1972 row is present in the repository result (as it will be for real once the
     * 1928-1971 seed rows land) but must be INVISIBLE to every default (no-arg) caller. Both
     * 1928 and 1972 fixture years are complete (all four classes) so the pre-1972 row doesn't
     * trip the "year missing a class" guard when it's excluded by the window filter.
     */
    @Test
    void matrix_defaultWindow_excludesPreDepressionYears() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1928, "us_stock", "0.46"), row(1928, "intl_stock", "0.46"),
                row(1928, "bond", "0.03"), row(1928, "cash", "0.05"),
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThat(provider.matrix().years()).containsExactly(1972);
        assertThat(provider.matrix(false).years()).containsExactly(1972);
    }

    @Test
    void matrix_extendedWindow_includesPreDepressionYears() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1928, "us_stock", "0.46"), row(1928, "intl_stock", "0.46"),
                row(1928, "bond", "0.03"), row(1928, "cash", "0.05"),
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThat(provider.matrix(true).years()).containsExactly(1928, 1972);
    }

    @Test
    void geometricMeans_defaultVsExtended_differWhenPreDepressionRowsPresent() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of(
                row(1928, "us_stock", "0.50"), row(1928, "intl_stock", "0.50"),
                row(1928, "bond", "0.50"), row(1928, "cash", "0.50"),
                row(1972, "us_stock", "0.10"), row(1972, "intl_stock", "0.08"),
                row(1972, "bond", "0.02"), row(1972, "cash", "0.01")));
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThat(provider.geometricMeans().get(AssetClass.US_STOCK)).isEqualTo(0.10, within(1e-9));
        assertThat(provider.geometricMeans(false).get(AssetClass.US_STOCK)).isEqualTo(0.10, within(1e-9));
        // sqrt(1.50 * 1.10) - 1
        assertThat(provider.geometricMeans(true).get(AssetClass.US_STOCK))
                .isEqualTo(Math.sqrt(1.50 * 1.10) - 1, within(1e-9));
    }

    @Test
    void matrix_extendedWindow_emptyBeyondDefaultFloor_throws() {
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(List.of());
        var provider = new CapitalMarketAssumptionsProvider(repo);

        assertThatThrownBy(() -> provider.matrix(true)).isInstanceOf(IllegalStateException.class);
    }

    // ===================================================================================
    // Audit C10 -- pins against the REAL 1928-2025 seed content (R__seed_asset_class_returns.sql),
    // embedded verbatim below as "year,class,real_return" so this unit test (no DB/Testcontainers)
    // exercises the SAME numbers production will load. See
    // .superpowers/sdd/audit2/t17-report.md for the full sourcing/verification and the hand-derived
    // arithmetic behind every pinned figure below.
    // ===================================================================================

    @Test
    void matrix_realSeedData_defaultWindow_isByteIdenticalToPreC10Baseline() {
        var provider = new CapitalMarketAssumptionsProvider(realSeedRepository());

        var m = provider.matrix();

        assertThat(m.years()).hasSize(54);
        assertThat(m.years()[0]).isEqualTo(1972);
        assertThat(m.years()[m.years().length - 1]).isEqualTo(2025);

        var means = provider.geometricMeans();
        // Pre-C10 pinned baseline (unchanged by seeding 1928-1971 -- the default window excludes them).
        assertThat(means.get(AssetClass.US_STOCK)).isEqualTo(0.06408525867815351, within(1e-9));
        assertThat(means.get(AssetClass.INTL_STOCK)).isEqualTo(0.052392116420025614, within(1e-9));
        assertThat(means.get(AssetClass.BOND)).isEqualTo(0.01908974706618438, within(1e-9));
        assertThat(means.get(AssetClass.CASH)).isEqualTo(0.005164775831502322, within(1e-9));
    }

    @Test
    void matrix_realSeedData_extendedWindow_spans1928To2025AndWidensTheEquityTail() {
        var provider = new CapitalMarketAssumptionsProvider(realSeedRepository());

        var m = provider.matrix(true);

        assertThat(m.years()).hasSize(98);
        assertThat(m.years()[0]).isEqualTo(1928);
        assertThat(m.years()[m.years().length - 1]).isEqualTo(2025);

        int us = Arrays.asList(m.classes()).indexOf(AssetClass.US_STOCK);
        double minUsStock = Arrays.stream(m.realReturns()).mapToDouble(r -> r[us]).min().orElseThrow();
        assertThat(minUsStock).isLessThan(-0.36);

        // 1931's real equity return specifically (Damodaran nominal -43.84% / CPI avg-of-year
        // deflation -9.0% -- see t17 report): the year the audit's "-38%" hint refers to. Not
        // itself the new all-time minimum (2008's -38.43% edges it out by 0.14pp) but the point
        // of C10 -- a resampled path can now land on either tail year.
        int idx1931 = Arrays.binarySearch(m.years(), 1931);
        assertThat(idx1931).isGreaterThanOrEqualTo(0);
        assertThat(m.realReturns()[idx1931][us]).isEqualTo(-0.382857, within(1e-6));

        var means = provider.geometricMeans(true);
        // Extended-window pinned geometric means (hand-verified in the t17 report). NOTE: equity
        // means shift UP, not down -- the 1928-1971 window's own geometric mean (us_stock 6.68%,
        // intl_stock 6.47%) is HIGHER than the 1972-2025 baseline (6.41%/5.24%), driven by the
        // deflation-amplified real returns of 1933/1935/1938/1954. Only bond/cash shift down.
        assertThat(means.get(AssetClass.US_STOCK)).isEqualTo(0.06530872939106858, within(1e-9));
        assertThat(means.get(AssetClass.INTL_STOCK)).isEqualTo(0.05789125923624727, within(1e-9));
        assertThat(means.get(AssetClass.BOND)).isEqualTo(0.014666759638503946, within(1e-9));
        assertThat(means.get(AssetClass.CASH)).isEqualTo(0.003386926672491075, within(1e-9));
    }

    /**
     * Parses the real {@code R__seed_asset_class_returns.sql} content (1928-2025, 98 years x 4
     * classes = 392 rows) into a mocked repository so provider-level tests exercise production
     * numbers without a database. Keep this text block in sync with the migration file; a drift
     * check lives in {@code AssetClassReturnsSeedIntegrationTest} (Testcontainers, real Postgres).
     */
    private static AssetClassReturnRepository realSeedRepository() {
        var rows = new ArrayList<AssetClassReturnEntity>();
        for (String line : REAL_SEED_CSV.strip().split("\n")) {
            String[] parts = line.split(",");
            rows.add(row(Integer.parseInt(parts[0]), parts[1], parts[2]));
        }
        var repo = mock(AssetClassReturnRepository.class);
        when(repo.findAllByOrderByYearAscAssetClassAsc()).thenReturn(rows);
        return repo;
    }

    private static final String REAL_SEED_CSV = """
            1928,us_stock,0.462970
            1928,intl_stock,0.462970
            1928,bond,0.025839
            1928,cash,0.048627
            1929,us_stock,-0.083000
            1929,intl_stock,-0.083000
            1929,bond,0.042000
            1929,cash,0.031600
            1930,us_stock,-0.233572
            1930,intl_stock,-0.233572
            1930,bond,0.070010
            1930,cash,0.070113
            1931,us_stock,-0.382857
            1931,intl_stock,-0.382857
            1931,bond,0.070769
            1931,cash,0.124286
            1932,us_stock,0.013984
            1932,intl_stock,0.013984
            1932,bond,0.207436
            1932,cash,0.121754
            1933,us_stock,0.580400
            1933,intl_stock,0.580400
            1933,bond,0.073340
            1933,cash,0.063857
            1934,us_stock,-0.041610
            1934,intl_stock,-0.041610
            1934,bond,0.047139
            1934,cash,-0.027352
            1935,us_stock,0.435812
            1935,intl_stock,0.435812
            1935,bond,0.022211
            1935,cash,-0.019863
            1936,us_stock,0.299901
            1936,intl_stock,0.299901
            1936,bond,0.034680
            1936,cash,-0.013103
            1937,us_stock,-0.375869
            1937,intl_stock,-0.375869
            1937,bond,-0.021429
            1937,cash,-0.032046
            1938,us_stock,0.320531
            1938,intl_stock,0.320531
            1938,bond,0.064454
            1938,cash,0.022165
            1939,us_stock,0.003043
            1939,intl_stock,0.003043
            1939,bond,0.058925
            1939,cash,0.014706
            1940,us_stock,-0.112910
            1940,intl_stock,-0.112910
            1940,bond,0.046673
            1940,cash,-0.006554
            1941,us_stock,-0.169238
            1941,intl_stock,-0.169238
            1941,bond,-0.066857
            1941,cash,-0.046381
            1942,us_stock,0.074572
            1942,intl_stock,0.074572
            1942,bond,-0.077638
            1942,cash,-0.095221
            1943,us_stock,0.178699
            1943,intl_stock,0.178699
            1943,bond,-0.034025
            1943,cash,-0.053911
            1944,us_stock,0.170403
            1944,intl_stock,0.170403
            1944,bond,0.008653
            1944,cash,-0.012979
            1945,us_stock,0.327664
            1945,intl_stock,0.327664
            1945,bond,0.014663
            1945,cash,-0.018768
            1946,us_stock,-0.154478
            1946,intl_stock,-0.154478
            1946,bond,-0.047738
            1946,cash,-0.073130
            1947,us_stock,-0.080420
            1947,intl_stock,-0.080420
            1947,bond,-0.117832
            1947,cash,-0.120629
            1948,us_stock,-0.022202
            1948,intl_stock,-0.022202
            1948,bond,-0.056892
            1948,cash,-0.065217
            1949,us_stock,0.197368
            1949,intl_stock,0.197368
            1949,bond,0.059312
            1949,cash,0.023482
            1950,us_stock,0.291313
            1950,intl_stock,0.291313
            1950,bond,-0.008588
            1950,cash,-0.000987
            1951,us_stock,0.146247
            1951,intl_stock,0.146247
            1951,bond,-0.075996
            1951,cash,-0.059129
            1952,us_stock,0.159470
            1952,intl_stock,0.159470
            1952,bond,0.003631
            1952,cash,-0.001766
            1953,us_stock,-0.019940
            1953,intl_stock,-0.019940
            1953,bond,0.033135
            1953,cash,0.010813
            1954,us_stock,0.514995
            1954,intl_stock,0.514995
            1954,bond,0.025720
            1954,cash,0.002383
            1955,us_stock,0.331325
            1955,intl_stock,0.331325
            1955,bond,-0.009438
            1955,cash,0.021285
            1956,us_stock,0.058522
            1956,intl_stock,0.058522
            1956,bond,-0.037044
            1956,cash,0.011034
            1957,us_stock,-0.133204
            1957,intl_stock,-0.133204
            1957,bond,0.033882
            1957,cash,-0.000774
            1958,us_stock,0.398054
            1958,intl_stock,0.398054
            1958,bond,-0.047665
            1958,cash,-0.010019
            1959,us_stock,0.112810
            1959,intl_stock,0.112810
            1959,bond,-0.033267
            1959,cash,0.026713
            1960,us_stock,-0.013373
            1960,intl_stock,-0.013373
            1960,bond,0.097738
            1960,cash,0.011504
            1961,us_stock,0.253861
            1961,intl_stock,0.253861
            1961,bond,0.010495
            1961,cash,0.013366
            1962,us_stock,-0.097129
            1962,intl_stock,-0.097129
            1962,bond,0.046436
            1962,cash,0.017525
            1963,us_stock,0.210365
            1963,intl_stock,0.210365
            1963,bond,0.003751
            1963,cash,0.018361
            1964,us_stock,0.149260
            1964,intl_stock,0.149260
            1964,bond,0.023988
            1964,cash,0.022211
            1965,us_stock,0.106299
            1965,intl_stock,0.106299
            1965,bond,-0.008661
            1965,cash,0.023130
            1966,us_stock,-0.125073
            1966,intl_stock,-0.125073
            1966,bond,0.000097
            1966,cash,0.019048
            1967,us_stock,0.200776
            1967,intl_stock,0.200776
            1967,bond,-0.045393
            1967,cash,0.011542
            1968,us_stock,0.063436
            1968,intl_stock,0.063436
            1968,bond,-0.008925
            1968,cash,0.010940
            1969,us_stock,-0.130237
            1969,intl_stock,-0.130237
            1969,bond,-0.099621
            1969,cash,0.011090
            1970,us_stock,-0.020246
            1970,intl_stock,-0.187606
            1970,bond,0.104541
            1970,cash,0.006528
            1971,us_stock,0.094061
            1971,intl_stock,0.208238
            1971,bond,0.051628
            1971,cash,-0.000670
            1972,us_stock,0.146700
            1972,intl_stock,0.332400
            1972,bond,-0.004379
            1972,cash,0.007628
            1973,us_stock,-0.229200
            1973,intl_stock,-0.191639
            1973,bond,-0.023713
            1973,cash,0.008121
            1974,us_stock,-0.360200
            1974,intl_stock,-0.298995
            1974,bond,-0.081625
            1974,cash,-0.028858
            1975,us_stock,0.250300
            1975,intl_stock,0.256148
            1975,bond,-0.050696
            1975,cash,-0.030722
            1976,us_stock,0.180700
            1976,intl_stock,-0.018959
            1976,bond,0.096791
            1976,cash,-0.007233
            1977,us_stock,-0.136300
            1977,intl_stock,0.121297
            1977,bond,-0.048935
            1977,cash,-0.011659
            1978,us_stock,-0.031900
            1978,intl_stock,0.247782
            1978,bond,-0.078146
            1978,cash,-0.004190
            1979,us_stock,0.058000
            1979,intl_stock,-0.045611
            1979,bond,-0.095137
            1979,cash,-0.010826
            1980,us_stock,0.177400
            1980,intl_stock,0.095825
            1980,bond,-0.145657
            1980,cash,-0.019016
            1981,us_stock,-0.156800
            1981,intl_stock,-0.103002
            1981,bond,-0.019348
            1981,cash,0.033582
            1982,us_stock,0.146800
            1982,intl_stock,-0.065875
            1982,bond,0.251373
            1982,cash,0.046721
            1983,us_stock,0.186000
            1983,intl_stock,0.207316
            1983,bond,-0.000120
            1983,cash,0.055590
            1984,us_stock,0.017500
            1984,intl_stock,0.034127
            1984,bond,0.090407
            1984,cash,0.053878
            1985,us_stock,0.265300
            1985,intl_stock,0.513535
            1985,bond,0.214054
            1985,cash,0.040314
            1986,us_stock,0.149400
            1986,intl_stock,0.667745
            1986,bond,0.219650
            1986,cash,0.041728
            1987,us_stock,-0.007800
            1987,intl_stock,0.205137
            1987,bond,-0.083197
            1987,cash,0.022143
            1988,us_stock,0.117600
            1988,intl_stock,0.235519
            1988,bond,0.039800
            1988,cash,0.027021
            1989,us_stock,0.266900
            1989,intl_stock,0.056980
            1989,bond,0.122707
            1989,cash,0.033989
            1990,us_stock,-0.099800
            1990,intl_stock,-0.271333
            1990,bond,0.007989
            1990,cash,0.022316
            1991,us_stock,0.268800
            1991,intl_stock,0.079292
            1991,bond,0.103277
            1991,cash,0.012520
            1992,us_stock,0.047700
            1992,intl_stock,-0.144414
            1992,bond,0.061451
            1992,cash,0.004670
            1993,us_stock,0.074500
            1993,intl_stock,0.291286
            1993,bond,0.109356
            1993,cash,0.001150
            1994,us_stock,-0.013300
            1994,intl_stock,0.053140
            1994,bond,-0.103769
            1994,cash,0.017178
            1995,us_stock,0.341600
            1995,intl_stock,0.085060
            1995,bond,0.201104
            1995,cash,0.027767
            1996,us_stock,0.190500
            1996,intl_stock,0.033312
            1996,bond,-0.014585
            1996,cash,0.021556
            1997,us_stock,0.312200
            1997,intl_stock,-0.002713
            1997,bond,0.074287
            1997,cash,0.027969
            1998,us_stock,0.263600
            1998,intl_stock,0.184907
            1998,bond,0.131634
            1998,cash,0.033064
            1999,us_stock,0.179000
            1999,intl_stock,0.245743
            1999,bond,-0.102145
            1999,cash,0.025365
            2000,us_stock,-0.129100
            2000,intl_stock,-0.167705
            2000,bond,0.128492
            2000,cash,0.025375
            2001,us_stock,-0.139900
            2001,intl_stock,-0.233755
            2001,bond,0.026684
            2001,cash,0.006359
            2002,us_stock,-0.239500
            2002,intl_stock,-0.169768
            2002,bond,0.133227
            2002,cash,0.000531
            2003,us_stock,0.264000
            2003,intl_stock,0.360808
            2003,bond,-0.018481
            2003,cash,-0.012126
            2004,us_stock,0.078800
            2004,intl_stock,0.175528
            2004,bond,0.017655
            2004,cash,-0.012439
            2005,us_stock,0.013500
            2005,intl_stock,0.102785
            2005,bond,-0.005056
            2005,cash,-0.001671
            2006,us_stock,0.130900
            2006,intl_stock,0.228955
            2006,bond,-0.012264
            2006,cash,0.015733
            2007,us_stock,0.018800
            2007,intl_stock,0.085339
            2007,bond,0.071533
            2007,cash,0.015822
            2008,us_stock,-0.384300
            2008,intl_stock,-0.451652
            2008,bond,0.156597
            2008,cash,-0.023489
            2009,us_stock,0.235200
            2009,intl_stock,0.329326
            2009,bond,-0.108029
            2009,cash,0.005074
            2010,us_stock,0.127100
            2010,intl_stock,0.064639
            2010,bond,0.067099
            2010,cash,-0.014758
            2011,us_stock,-0.002100
            2011,intl_stock,-0.144313
            2011,bond,0.124889
            2011,cash,-0.030118
            2012,us_stock,0.130000
            2012,intl_stock,0.155097
            2012,bond,0.008824
            2012,cash,-0.019392
            2013,us_stock,0.298500
            2013,intl_stock,0.215101
            2013,bond,-0.104123
            2013,cash,-0.013846
            2014,us_stock,0.113900
            2014,intl_stock,-0.060048
            2014,bond,0.089821
            2014,cash,-0.015668
            2015,us_stock,-0.007300
            2015,intl_stock,-0.005080
            2015,bond,0.011600
            2015,cash,-0.000685
            2016,us_stock,0.095400
            2016,intl_stock,0.002453
            2016,bond,-0.005645
            2016,cash,-0.009299
            2017,us_stock,0.194200
            2017,intl_stock,0.230000
            2017,bond,0.006559
            2017,cash,-0.011555
            2018,us_stock,-0.065700
            2018,intl_stock,-0.154258
            2018,bond,-0.024039
            2018,cash,-0.004613
            2019,us_stock,0.288100
            2019,intl_stock,0.204767
            2019,bond,0.076885
            2019,cash,0.002925
            2020,us_stock,0.156400
            2020,intl_stock,0.069606
            2020,bond,0.099734
            2020,cash,-0.008629
            2021,us_stock,0.212800
            2021,intl_stock,0.067644
            2021,bond,-0.087087
            2021,cash,-0.044489
            2022,us_stock,-0.193000
            2022,intl_stock,-0.203817
            2022,bond,-0.239186
            2022,cash,-0.054747
            2023,us_stock,0.241300
            2023,intl_stock,0.141512
            2023,bond,-0.002270
            2023,cash,0.011177
            2024,us_stock,0.228900
            2024,intl_stock,0.013604
            2024,bond,-0.044580
            2024,cash,0.021666
            2025,us_stock,0.018700
            2025,intl_stock,0.285085
            2025,bond,0.050362
            2025,cash,0.015382
            """;
}
