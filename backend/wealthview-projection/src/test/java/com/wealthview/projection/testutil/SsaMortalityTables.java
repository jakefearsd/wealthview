package com.wealthview.projection.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wealthview.core.projection.mortality.MortalityTable;

/**
 * Loads the REAL SSA period-life {@link MortalityTable} used by the stochastic-mortality Monte
 * Carlo engine, parsed straight from the production Flyway seed ({@code
 * db/migration/R__seed_mortality_rates.sql}, on the test classpath via the wealthview-persistence
 * dependency). Parsing the seed rather than transcribing the ~160 qx values keeps ONE source of
 * truth: a seed-pinned golden built on this table can never silently drift away from the data the
 * production {@code MortalityTableProvider} loads at runtime.
 */
public final class SsaMortalityTables {

    private static final String SEED_RESOURCE = "db/migration/R__seed_mortality_rates.sql";

    /** Matches a seed tuple like {@code ('male', 40, 0.003780)} / {@code ('female', 120, 1.00000000)}. */
    private static final Pattern ROW =
            Pattern.compile("\\('(male|female)',\\s*(\\d+),\\s*([0-9.]+)\\)");

    private SsaMortalityTables() {
    }

    /** Parses the seed SQL into a two-sex {@link MortalityTable}. */
    public static MortalityTable load() {
        var sql = readSeed();
        Map<Integer, Double> male = new HashMap<>();
        Map<Integer, Double> female = new HashMap<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) {
            int age = Integer.parseInt(m.group(2));
            double qx = Double.parseDouble(m.group(3));
            if ("male".equals(m.group(1))) {
                male.put(age, qx);
            } else {
                female.put(age, qx);
            }
        }
        if (male.isEmpty() || female.isEmpty()) {
            throw new IllegalStateException("Parsed no rows from " + SEED_RESOURCE + "; seed format changed?");
        }
        return new MortalityTable(male, female);
    }

    private static String readSeed() {
        try (var is = SsaMortalityTables.class.getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found on test classpath: " + SEED_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
