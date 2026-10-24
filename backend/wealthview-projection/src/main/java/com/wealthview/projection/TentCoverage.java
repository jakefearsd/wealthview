package com.wealthview.projection;

/**
 * The pure arithmetic of the tent's coverage metrics, shared by the deterministic replay and Monte Carlo so both
 * measure "years covered" the same way.
 */
final class TentCoverage {

    private TentCoverage() {
    }

    /** The share of a planned withdrawal the tent paid; zero when nothing was planned. */
    static double drawnShare(double drawn, double planned) {
        return planned <= 0.0 ? 0.0 : drawn / planned;
    }

    /** What is left in the tent, in years of the mean planned withdrawal; zero when that mean is zero. */
    static double unusedYears(double left, double... planned) {
        double total = 0.0;
        for (double withdrawal : planned) {
            total += withdrawal;
        }
        double mean = planned.length == 0 ? 0.0 : total / planned.length;
        return mean == 0.0 ? 0.0 : left / mean;
    }
}
