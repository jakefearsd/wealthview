package com.wealthview.projection;

import java.util.ArrayDeque;
import java.util.Deque;

/** FIFO cost-basis lots for a taxable pool. Each lot is [basis, value]; oldest first. */
final class TaxableLots {

    private final Deque<double[]> lots = new ArrayDeque<>();

    void addLot(double amount) {
        if (amount > 0) {
            lots.addLast(new double[]{amount, amount});
        }
    }

    void grow(double appreciationRate) {
        double factor = 1 + appreciationRate;
        for (double[] lot : lots) {
            lot[1] *= factor;
        }
    }

    double totalValue() {
        double v = 0;
        for (double[] lot : lots) {
            v += lot[1];
        }
        return v;
    }

    double totalBasis() {
        double b = 0;
        for (double[] lot : lots) {
            b += lot[0];
        }
        return b;
    }

    /** Sells {@code amount} of value oldest-first; returns the realized long-term gain. */
    double sellFifo(double amount) {
        double remaining = Math.min(amount, totalValue());
        double gain = 0;
        while (remaining > 1e-12 && !lots.isEmpty()) {
            double[] lot = lots.peekFirst();
            double basis = lot[0];
            double value = lot[1];
            if (value <= remaining + 1e-12) {
                gain += value - basis;   // whole lot sold
                remaining -= value;
                lots.removeFirst();
            } else {
                double sold = remaining;
                double soldBasis = basis * (sold / value);
                gain += sold - soldBasis;
                lot[0] = basis - soldBasis;
                lot[1] = value - sold;
                remaining = 0;
            }
        }
        return gain;
    }

    void consolidateIfNeeded(int cap) {
        if (lots.size() <= cap) {
            return;
        }
        int toMerge = lots.size() - cap + 1;
        double basis = 0;
        double value = 0;
        for (int i = 0; i < toMerge; i++) {
            double[] lot = lots.removeFirst();
            basis += lot[0];
            value += lot[1];
        }
        lots.addFirst(new double[]{basis, value});
    }
}
