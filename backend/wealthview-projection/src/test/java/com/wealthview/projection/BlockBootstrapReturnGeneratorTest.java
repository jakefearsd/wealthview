package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BlockBootstrapReturnGeneratorTest {

    private final double[] historicalReturns = HistoricalReturns.getReturns();

    @Test
    void generateReturnSequence_correctLength() {
        var generator = new BlockBootstrapReturnGenerator(historicalReturns, 5.0, new Random(42));

        double[] sequence = generator.generateReturnSequence(30);

        assertThat(sequence).hasSize(30);
    }

    @Test
    void generateReturnSequence_valuesFromHistoricalData() {
        var generator = new BlockBootstrapReturnGenerator(historicalReturns, 5.0, new Random(42));
        Set<Double> historicalSet = Arrays.stream(historicalReturns).boxed().collect(Collectors.toSet());

        double[] sequence = generator.generateReturnSequence(30);

        for (double value : sequence) {
            assertThat(historicalSet)
                    .as("Generated value %.4f should exist in historical data", value)
                    .contains(value);
        }
    }

    @Test
    void generateReturnSequence_withSeed_reproducible() {
        var gen1 = new BlockBootstrapReturnGenerator(historicalReturns, 5.0, new Random(123));
        var gen2 = new BlockBootstrapReturnGenerator(historicalReturns, 5.0, new Random(123));

        double[] seq1 = gen1.generateReturnSequence(30);
        double[] seq2 = gen2.generateReturnSequence(30);

        assertThat(seq1).isEqualTo(seq2);
    }

    @Test
    void generateReturnSequence_blockStructure_adjacentReturnsOftenConsecutive() {
        var generator = new BlockBootstrapReturnGenerator(historicalReturns, 5.0, new Random(42));

        // Generate many sequences and count how often adjacent returns are consecutive
        // in the historical array (indicating they came from the same block)
        int totalPairs = 0;
        int consecutivePairs = 0;

        for (int trial = 0; trial < 100; trial++) {
            double[] sequence = generator.generateReturnSequence(30);
            for (int i = 0; i < sequence.length - 1; i++) {
                totalPairs++;
                int idx1 = findIndex(historicalReturns, sequence[i]);
                int idx2 = findIndex(historicalReturns, sequence[i + 1]);
                // Consecutive in historical array (with wrapping)
                if (idx1 >= 0 && idx2 >= 0
                        && ((idx2 == idx1 + 1) || (idx1 == historicalReturns.length - 1 && idx2 == 0))) {
                    consecutivePairs++;
                }
            }
        }

        double consecutiveRate = (double) consecutivePairs / totalPairs;
        // With expected block length 5, roughly 80% (1 - 1/5) of adjacent pairs should be consecutive
        // Allow generous tolerance for randomness
        assertThat(consecutiveRate)
                .as("Consecutive pair rate should be roughly 80%% (1-1/blockLen), got %.1f%%",
                        consecutiveRate * 100)
                .isBetween(0.60, 0.95);
    }

    @Test
    void generateReturnSequence_wrapsAtEnd() {
        // Use a very short "historical" array to force wrapping
        double[] shortHistory = {0.10, 0.05, -0.03, 0.08, 0.12};
        // Block length = 100 (never start new block) forces continuous advance with wrapping
        var generator = new BlockBootstrapReturnGenerator(shortHistory, 100.0, new Random(42));

        double[] sequence = generator.generateReturnSequence(10);

        assertThat(sequence).hasSize(10);
        // All values should come from the short history
        Set<Double> histSet = Arrays.stream(shortHistory).boxed().collect(Collectors.toSet());
        for (double v : sequence) {
            assertThat(histSet).contains(v);
        }
    }

    private int findIndex(double[] array, double value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
