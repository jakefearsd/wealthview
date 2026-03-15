package com.wealthview.projection;

import java.util.Random;

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
        int n = historicalReturns.length;
        double switchProb = 1.0 / expectedBlockLength;

        int currentIndex = rng.nextInt(n);

        for (int y = 0; y < years; y++) {
            if (y > 0 && rng.nextDouble() < switchProb) {
                currentIndex = rng.nextInt(n);
            }
            sequence[y] = historicalReturns[currentIndex];
            currentIndex = (currentIndex + 1) % n;
        }

        return sequence;
    }
}
