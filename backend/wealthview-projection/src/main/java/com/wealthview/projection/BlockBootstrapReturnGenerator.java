package com.wealthview.projection;

import java.util.Arrays;
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
        this.historicalReturns = Arrays.copyOf(historicalReturns, historicalReturns.length);
        this.expectedBlockLength = expectedBlockLength;
        this.rng = rng;
    }

    /**
     * Index-only constructor for callers that draw {@link #generateIndexSequence} against an
     * externally sized series (e.g. the multi-asset capital-market matrix) and never call
     * {@link #generateReturnSequence}. No historical return array is needed.
     */
    public BlockBootstrapReturnGenerator(double expectedBlockLength, Random rng) {
        this(new double[0], expectedBlockLength, rng);
    }

    /**
     * Generates an array of indices into the historical series using the block-bootstrap algorithm.
     *
     * @param years the number of indices to generate
     * @param historicalSize the size of the historical data (max index will be historicalSize - 1)
     * @return an array of indices sampled with block-bootstrap logic
     */
    public int[] generateIndexSequence(int years, int historicalSize) {
        int[] indices = new int[years];
        double blockTerminationProbability = 1.0 / expectedBlockLength;
        int currentIndex = rng.nextInt(historicalSize);

        for (int y = 0; y < years; y++) {
            if (y > 0 && rng.nextDouble() < blockTerminationProbability) {
                currentIndex = rng.nextInt(historicalSize);
            }
            indices[y] = currentIndex;
            currentIndex = (currentIndex + 1) % historicalSize;
        }

        return indices;
    }

    public double[] generateReturnSequence(int years) {
        int[] idx = generateIndexSequence(years, historicalReturns.length);
        double[] sequence = new double[years];
        for (int y = 0; y < years; y++) {
            sequence[y] = historicalReturns[idx[y]];
        }
        return sequence;
    }
}
