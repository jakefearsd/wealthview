package com.wealthview.projection;

import java.util.Random;

/**
 * Generates synthetic annual return sequences using the <em>block bootstrap</em> method.
 *
 * <p>Rather than sampling individual years independently (i.i.d.), the generator samples
 * consecutive <em>blocks</em> from the historical return series. This preserves return
 * autocorrelation — momentum and mean-reversion patterns — that an i.i.d. draw would destroy,
 * making the simulated sequences more realistic for sequence-of-returns risk analysis.
 *
 * <p><strong>Algorithm:</strong> At each year, a geometric random variable determines whether
 * to start a new block at a random position in the historical series or continue sequentially
 * within the current block. The block termination probability is {@code 1 / expectedBlockLength},
 * so the expected number of years drawn from each block equals {@code expectedBlockLength}.
 */
public class BlockBootstrapReturnGenerator {

    private final double[] historicalReturns;
    private final double expectedBlockLength;
    private final Random rng;

    public BlockBootstrapReturnGenerator(double[] historicalReturns, double expectedBlockLength, Random rng) {
        this.historicalReturns = historicalReturns;
        this.expectedBlockLength = expectedBlockLength;
        this.rng = rng;
    }

    public double[] generateReturnSequence(int years) {
        double[] sequence = new double[years];
        int historicalReturnCount = historicalReturns.length;

        // Probability of ending the current block and jumping to a new random start position.
        // Geometric distribution: expected block length = 1 / blockTerminationProbability.
        double blockTerminationProbability = 1.0 / expectedBlockLength;

        int currentIndex = rng.nextInt(historicalReturnCount);

        for (int y = 0; y < years; y++) {
            if (y > 0 && rng.nextDouble() < blockTerminationProbability) {
                // Start a new block at a uniformly random position in the historical series.
                currentIndex = rng.nextInt(historicalReturnCount);
            }
            sequence[y] = historicalReturns[currentIndex];
            // Advance sequentially within the current block, wrapping at the end of the series.
            currentIndex = (currentIndex + 1) % historicalReturnCount;
        }

        return sequence;
    }
}
