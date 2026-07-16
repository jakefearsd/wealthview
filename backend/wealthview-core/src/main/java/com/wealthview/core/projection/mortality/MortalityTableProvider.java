package com.wealthview.core.projection.mortality;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.wealthview.persistence.projection.MortalityRateEntity;
import com.wealthview.persistence.projection.MortalityRateRepository;

/**
 * Loads the {@link MortalityTable} (both sexes) from {@link MortalityRateRepository} once and
 * caches it for the life of the singleton -- the same lazily-populated-field idiom this module
 * already uses for other no-arg, load-once reference-data providers (e.g.
 * {@code CapitalMarketAssumptionsProvider#matrix()}, which caches via {@link AtomicReference}).
 * {@code mortality_rates} is static Flyway-seeded reference data ({@code
 * R__seed_mortality_rates.sql}), so unlike the per-(year, filing-status) {@code ConcurrentHashMap}
 * caches in {@code FederalTaxCalculator} / {@code IrmaaSurchargeCalculator}, there is no key to
 * cache by -- a single cached instance IS the whole table.
 */
@Service
public class MortalityTableProvider {

    private static final String MALE = "male";
    private static final String FEMALE = "female";

    private final MortalityRateRepository mortalityRateRepository;
    private final AtomicReference<MortalityTable> cachedTable = new AtomicReference<>();

    public MortalityTableProvider(MortalityRateRepository mortalityRateRepository) {
        this.mortalityRateRepository = mortalityRateRepository;
    }

    /** Loads both sexes from the repository once and caches the result for subsequent calls. */
    public MortalityTable load() {
        var existing = cachedTable.get();
        if (existing != null) {
            return existing;
        }
        var built = buildTable();
        cachedTable.set(built);
        return built;
    }

    void clearCache() {
        cachedTable.set(null);
    }

    private MortalityTable buildTable() {
        return new MortalityTable(loadQxMap(MALE), loadQxMap(FEMALE));
    }

    private Map<Integer, Double> loadQxMap(String sex) {
        var entities = mortalityRateRepository.findAllBySexOrderByAgeAsc(sex);
        if (entities.isEmpty()) {
            throw new IllegalStateException("mortality_rates is empty for sex " + sex + "; seed data missing");
        }
        return entities.stream()
                .collect(Collectors.toMap(MortalityRateEntity::getAge, e -> e.getQx().doubleValue()));
    }
}
