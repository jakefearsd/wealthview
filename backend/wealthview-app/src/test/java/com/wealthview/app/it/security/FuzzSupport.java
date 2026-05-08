package com.wealthview.app.it.security;

import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Shared utilities for *FuzzIT classes.
 *
 * <p>jqwik's @Property engine doesn't compose cleanly with @SpringBootTest's
 * Spring TestContextManager (each engine wants to own the JUnit lifecycle and
 * jqwik's Arbitrary generators store state on a per-property thread-local).
 * Fuzz tests here are plain JUnit Jupiter @Test methods that drive a
 * deterministic sequence of values through a seeded {@link Random} and small
 * generator helpers. We give up shrinking in exchange for clean Spring
 * lifecycle integration and easy debuggability when a counter-example fires.
 *
 * <p>Determinism is critical: a flaky property failure that can't be reproduced
 * is worse than no test at all. All fuzz tests use a fixed master seed and
 * derive per-test seeds from it so failures bisect cleanly.
 */
final class FuzzSupport {

    /** Master seed for the entire fuzz suite. Bump only when intentional. */
    static final long MASTER_SEED = 0x5ECF0FA11BACEL;

    private FuzzSupport() {
    }

    /**
     * Pull a deterministic stream of {@code count} samples from {@code generator}.
     * The {@link Random} is seeded by XOR'ing the master seed with the
     * caller-supplied {@code testSeedOffset} so different fuzz methods do not
     * all walk the same sequence.
     */
    static <T> Stream<T> samples(Function<Random, T> generator, int count, long testSeedOffset) {
        var random = new Random(MASTER_SEED ^ testSeedOffset);
        return Stream.generate(() -> generator.apply(random)).limit(count);
    }

    /** Random alphanumeric string of length in [0, maxLen]. */
    static String alnum(Random r, int maxLen) {
        int len = r.nextInt(maxLen + 1);
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int c = r.nextInt(62);
            char ch = c < 26 ? (char) ('a' + c)
                    : c < 52 ? (char) ('A' + c - 26)
                    : (char) ('0' + c - 52);
            sb.append(ch);
        }
        return sb.toString();
    }

    /** Random ASCII string spanning printable + control chars in [0, maxLen]. */
    static String asciiNoisy(Random r, int maxLen) {
        int len = r.nextInt(maxLen + 1);
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (r.nextInt(127 - 32) + 32));
        }
        return sb.toString();
    }

    /** Pick one element from the given options. */
    @SafeVarargs
    static <T> T choose(Random r, T... options) {
        return options[r.nextInt(options.length)];
    }
}
