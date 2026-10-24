package com.wealthview.projection.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.wealthview.core.projection.BondMaturityReturnsProvider;
import com.wealthview.core.projection.BondTentAssumptions;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.CrashReplayPaths;
import com.wealthview.persistence.entity.AssetClassReturnEntity;
import com.wealthview.persistence.entity.BondMaturityReturnEntity;
import com.wealthview.persistence.repository.AssetClassReturnRepository;
import com.wealthview.persistence.repository.BondMaturityReturnRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real return providers over the rows of the two seed migrations, read from the classpath, so a golden that
 * replays history pins the seeded series without a database.
 */
public final class SeedReturns {

    private static final String ASSET_SEED = "db/migration/R__seed_asset_class_returns.sql";
    private static final String MATURITY_SEED = "db/migration/R__seed_bond_maturity_returns.sql";
    private static final Pattern ROW = Pattern.compile("^\\s*\\((\\d{4}),\\s*'([a-z_]+)',\\s*(-?\\d+\\.\\d+)\\)");

    private SeedReturns() {
    }

    public static CapitalMarketAssumptionsProvider capitalMarket() {
        var rows = new ArrayList<AssetClassReturnEntity>();
        for (var line : readLines(ASSET_SEED)) {
            var m = ROW.matcher(line);
            if (m.find()) {
                rows.add(new AssetClassReturnEntity(Integer.parseInt(m.group(1)), m.group(2),
                        new BigDecimal(m.group(3))));
            }
        }
        var repository = mock(AssetClassReturnRepository.class);
        when(repository.findAllByOrderByYearAscAssetClassAsc()).thenReturn(rows);
        return new CapitalMarketAssumptionsProvider(repository);
    }

    public static BondMaturityReturnsProvider maturityReturns() {
        var rows = new ArrayList<BondMaturityReturnEntity>();
        for (var line : readLines(MATURITY_SEED)) {
            var m = ROW.matcher(line);
            if (m.find()) {
                rows.add(new BondMaturityReturnEntity(Integer.parseInt(m.group(1)), m.group(2),
                        new BigDecimal(m.group(3))));
            }
        }
        var repository = mock(BondMaturityReturnRepository.class);
        when(repository.findAllByOrderByYearAscMaturityAsc()).thenReturn(rows);
        return new BondMaturityReturnsProvider(repository);
    }

    public static BondTentAssumptions tentAssumptions(CapitalMarketAssumptionsProvider capitalMarket,
                                                      BondMaturityReturnsProvider maturityReturns) {
        return new BondTentAssumptions(capitalMarket, maturityReturns);
    }

    public static CrashReplayPaths crashReplayPaths(CapitalMarketAssumptionsProvider capitalMarket,
                                                    BondMaturityReturnsProvider maturityReturns) {
        return new CrashReplayPaths(capitalMarket, maturityReturns,
                tentAssumptions(capitalMarket, maturityReturns));
    }

    private static List<String> readLines(String resource) {
        try (var in = SeedReturns.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Seed not on the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
