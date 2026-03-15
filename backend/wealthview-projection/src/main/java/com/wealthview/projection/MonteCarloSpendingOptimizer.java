package com.wealthview.projection;

import com.wealthview.core.projection.SpendingOptimizer;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Component
public class MonteCarloSpendingOptimizer implements SpendingOptimizer {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloSpendingOptimizer.class);
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Override
    public GuardrailProfileResponse optimize(GuardrailOptimizationInput input) {
        int retirementYear = input.retirementDate().getYear();
        int retirementAge = retirementYear - input.birthYear();
        int endAge = input.endAge();
        int years = endAge - retirementAge;

        if (years <= 0) {
            return emptyResult(input);
        }

        double returnMean = input.returnMean().doubleValue();
        double returnStddev = input.returnStddev().doubleValue();
        int trialCount = input.trialCount();
        double initialPortfolio = totalPortfolio(input.accounts());
        double essentialFloor = input.essentialFloor().doubleValue();
        double terminalTarget = input.terminalBalanceTarget().doubleValue();
        double confidenceLevel = input.confidenceLevel().doubleValue();

        Random rng = input.seed() != null ? new Random(input.seed()) : new Random();

        // Log-normal parameters
        double mu = Math.log(1 + returnMean) - 0.5 * returnStddev * returnStddev;
        double sigma = returnStddev;

        // Stage 1: Run MC trials (no withdrawals) to get portfolio trajectories
        double[][] portfolioPaths = runMonteCarloTrials(
                trialCount, years, initialPortfolio, mu, sigma, rng);

        // Compute deterministic income for each year
        double[] incomeByYear = computeDeterministicIncome(
                input.incomeSources(), retirementAge, years, input.birthYear());

        // Stage 2: Verify essential floor feasibility
        double[] adjustedFloors = verifyEssentialFloor(
                portfolioPaths, incomeByYear, essentialFloor, terminalTarget,
                confidenceLevel, years, trialCount);

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        // Stage 3: Priority-weighted discretionary allocation
        double[] discretionaryByYear = allocateSpending(
                portfolioPaths, incomeByYear, adjustedFloors, terminalTarget,
                input.phases(), retirementAge, years, trialCount,
                confidenceLevel, portfolioFloor);

        // Stage 4: Post-processing — phase blending and YoY smoothing
        int phaseBlendYears = input.phaseBlendYears();
        if (phaseBlendYears > 0 && input.phases() != null && input.phases().size() > 1) {
            applyPhaseBlending(discretionaryByYear, adjustedFloors, input.phases(),
                    retirementAge, years, phaseBlendYears);
        }

        Double maxAdjRate = input.maxAnnualAdjustmentRate() != null
                ? input.maxAnnualAdjustmentRate().doubleValue() : null;
        if (maxAdjRate != null && maxAdjRate > 0) {
            applyYearOverYearSmoothing(discretionaryByYear, adjustedFloors, maxAdjRate, years);

            // Re-verify sustainability of smoothed plan; reduce if broken
            if (!isSustainable(portfolioPaths, incomeByYear, adjustedFloors,
                    discretionaryByYear, terminalTarget, years, trialCount,
                    confidenceLevel, portfolioFloor)) {
                for (int i = 0; i < 10; i++) {
                    for (int y = 0; y < years; y++) {
                        discretionaryByYear[y] *= 0.95;
                    }
                    if (isSustainable(portfolioPaths, incomeByYear, adjustedFloors,
                            discretionaryByYear, terminalTarget, years, trialCount,
                            confidenceLevel, portfolioFloor)) {
                        break;
                    }
                }
            }
        }

        // Compute corridors + corridor smoothing
        double[][] corridors = computeCorridors(
                portfolioPaths, incomeByYear, adjustedFloors, discretionaryByYear,
                terminalTarget, years, trialCount);
        smoothCorridors(corridors[0], corridors[1], years);

        // Simulate with withdrawals to get final balances and per-year median balances
        var rng2 = input.seed() != null ? new Random(input.seed()) : rng;
        double[][] yearBalances = new double[years][trialCount];
        double[] finalBalances = new double[trialCount];
        for (int t = 0; t < trialCount; t++) {
            double balance = initialPortfolio;
            for (int y = 0; y < years; y++) {
                double logReturn = mu + sigma * rng2.nextGaussian();
                double growthFactor = Math.exp(logReturn);
                double spending = adjustedFloors[y] + discretionaryByYear[y];
                double withdrawal = Math.max(0, spending - incomeByYear[y]);
                balance = Math.max(0, balance * growthFactor - withdrawal);
                yearBalances[y][t] = balance;
            }
            finalBalances[t] = balance;
        }

        double[] medianBalanceByYear = new double[years];
        for (int y = 0; y < years; y++) {
            Arrays.sort(yearBalances[y]);
            medianBalanceByYear[y] = percentile(yearBalances[y], 0.50);
        }

        Arrays.sort(finalBalances);
        double medianFinal = percentile(finalBalances, 0.50);
        double p10Final = percentile(finalBalances, 0.10);
        double p90Final = percentile(finalBalances, 0.90);
        long failures = Arrays.stream(finalBalances).filter(b -> b <= 0).count();
        double failureRate = (double) failures / trialCount;

        // Build yearly spending records
        var yearlySpending = new ArrayList<GuardrailYearlySpending>();
        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int calendarYear = retirementYear + y;
            double floor = adjustedFloors[y];
            double disc = discretionaryByYear[y];
            double recommended = floor + disc;
            double income = incomeByYear[y];
            double withdrawal = Math.max(0, recommended - income);
            String phaseName = findPhaseName(input.phases(), age);

            yearlySpending.add(new GuardrailYearlySpending(
                    calendarYear, age,
                    toBD(recommended), toBD(corridors[0][y]), toBD(corridors[1][y]),
                    toBD(floor), toBD(disc), toBD(income), toBD(withdrawal), phaseName,
                    toBD(medianBalanceByYear[y])));
        }

        log.info("MC optimization complete: {} trials, {} years, median final balance {}",
                trialCount, years, toBD(medianFinal));

        return new GuardrailProfileResponse(
                null, null, input.phases().isEmpty() ? "Optimized" : "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                input.returnMean(), input.returnStddev(),
                trialCount, input.confidenceLevel(),
                input.phases(), yearlySpending,
                toBD(medianFinal), toBD(failureRate),
                toBD(p10Final), toBD(p90Final),
                false, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private double[][] runMonteCarloTrials(int trialCount, int years,
                                            double initialPortfolio,
                                            double mu, double sigma, Random rng) {
        double[][] paths = new double[trialCount][years + 1];
        for (int t = 0; t < trialCount; t++) {
            paths[t][0] = initialPortfolio;
            for (int y = 0; y < years; y++) {
                double logReturn = mu + sigma * rng.nextGaussian();
                double growthFactor = Math.exp(logReturn);
                paths[t][y + 1] = paths[t][y] * growthFactor;
            }
        }
        return paths;
    }

    private double[] computeDeterministicIncome(List<ProjectionIncomeSourceInput> sources,
                                                 int retirementAge, int years, int birthYear) {
        double[] income = new double[years];
        if (sources == null || sources.isEmpty()) {
            return income;
        }

        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            int yearsInRetirement = y + 1;
            for (var source : sources) {
                if (!isActiveForAge(source, age)) {
                    continue;
                }
                double amount = source.annualAmount().doubleValue();
                if (source.inflationRate() != null
                        && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
                    amount *= Math.pow(1 + source.inflationRate().doubleValue(), yearsInRetirement - 1);
                }
                // Apply boundary multiplier
                if (age == source.startAge() || (source.endAge() != null && age == source.endAge())) {
                    amount *= 0.5;
                }
                income[y] += amount;
            }
        }
        return income;
    }

    private boolean isActiveForAge(ProjectionIncomeSourceInput source, int age) {
        if (age < source.startAge()) {
            return false;
        }
        if (source.endAge() != null && age > source.endAge()) {
            return false;
        }
        if (source.oneTime() && age != source.startAge()) {
            return false;
        }
        return true;
    }

    private double[] verifyEssentialFloor(double[][] paths, double[] income,
                                           double essentialFloor, double terminalTarget,
                                           double confidenceLevel, int years, int trialCount) {
        double[] floors = new double[years];
        int confidenceIndex = (int) Math.ceil((1 - confidenceLevel) * trialCount) - 1;
        confidenceIndex = Math.max(0, Math.min(confidenceIndex, trialCount - 1));

        for (int y = 0; y < years; y++) {
            double[] balancesAtYear = new double[trialCount];
            for (int t = 0; t < trialCount; t++) {
                // Subtract cumulative floor withdrawals up to this year
                double cumulativeWithdrawal = 0;
                for (int py = 0; py <= y; py++) {
                    cumulativeWithdrawal += Math.max(0, essentialFloor - income[py]);
                }
                balancesAtYear[t] = paths[t][y + 1] - cumulativeWithdrawal;
            }
            Arrays.sort(balancesAtYear);

            double availableAtConfidence = balancesAtYear[confidenceIndex];
            double pvTerminal = terminalTarget;
            double capacityForFloor = availableAtConfidence - pvTerminal + income[y];

            if (capacityForFloor >= essentialFloor) {
                floors[y] = essentialFloor;
            } else {
                floors[y] = Math.max(0, Math.min(essentialFloor, capacityForFloor));
            }
        }
        return floors;
    }

    private double[] allocateSpending(double[][] paths, double[] income,
                                       double[] floors, double terminalTarget,
                                       List<GuardrailPhaseInput> phases,
                                       int retirementAge, int years, int trialCount,
                                       double confidenceLevel, double portfolioFloor) {
        double[] discretionary = new double[years];

        if (phases == null || phases.isEmpty()) {
            double maxDisc = binarySearchDiscretionary(
                    paths, income, floors, discretionary, terminalTarget,
                    0, years - 1, years, trialCount, confidenceLevel, portfolioFloor);
            Arrays.fill(discretionary, maxDisc);
            return discretionary;
        }

        // Check if any phase has target spending — use target-based allocation
        boolean hasTargets = phases.stream()
                .anyMatch(p -> p.targetSpending() != null
                        && p.targetSpending().compareTo(BigDecimal.ZERO) > 0);

        if (hasTargets) {
            return allocateByTargets(paths, income, floors, terminalTarget, phases,
                    retirementAge, years, trialCount, confidenceLevel, portfolioFloor);
        }

        // Legacy: sort phases by priority weight (highest first)
        var sortedPhases = phases.stream()
                .sorted(Comparator.comparingInt(GuardrailPhaseInput::priorityWeight).reversed())
                .toList();

        for (var phase : sortedPhases) {
            int phaseStart = phase.startAge() - retirementAge;
            int phaseEnd = phase.endAge() != null
                    ? Math.min(phase.endAge() - retirementAge, years - 1)
                    : years - 1;
            phaseStart = Math.max(0, phaseStart);
            phaseEnd = Math.min(phaseEnd, years - 1);

            if (phaseStart > phaseEnd) {
                continue;
            }

            double maxDisc = binarySearchDiscretionary(
                    paths, income, floors, discretionary, terminalTarget,
                    phaseStart, phaseEnd, years, trialCount, confidenceLevel, portfolioFloor);

            for (int y = phaseStart; y <= phaseEnd; y++) {
                discretionary[y] = maxDisc;
            }
        }

        return discretionary;
    }

    private double[] allocateByTargets(double[][] paths, double[] income,
                                        double[] floors, double terminalTarget,
                                        List<GuardrailPhaseInput> phases,
                                        int retirementAge, int years, int trialCount,
                                        double confidenceLevel, double portfolioFloor) {
        // Compute target discretionary for each year based on phase targets
        double[] targetDisc = new double[years];
        for (int y = 0; y < years; y++) {
            int age = retirementAge + y;
            for (var phase : phases) {
                if (age >= phase.startAge()
                        && (phase.endAge() == null || age <= phase.endAge())) {
                    if (phase.targetSpending() != null) {
                        targetDisc[y] = Math.max(0,
                                phase.targetSpending().doubleValue() - floors[y]);
                    }
                    break;
                }
            }
        }

        // Binary search for a single scaling factor s in [0, 2]
        double low = 0;
        double high = 2.0;

        for (int iter = 0; iter < 40; iter++) {
            double mid = (low + high) / 2;
            double[] testDisc = new double[years];
            for (int y = 0; y < years; y++) {
                testDisc[y] = mid * targetDisc[y];
            }

            if (isSustainable(paths, income, floors, testDisc, terminalTarget,
                    years, trialCount, confidenceLevel, portfolioFloor)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        double[] discretionary = new double[years];
        for (int y = 0; y < years; y++) {
            discretionary[y] = low * targetDisc[y];
        }
        return discretionary;
    }

    private double binarySearchDiscretionary(double[][] paths, double[] income,
                                              double[] floors, double[] currentDiscretionary,
                                              double terminalTarget,
                                              int phaseStart, int phaseEnd,
                                              int years, int trialCount,
                                              double confidenceLevel, double portfolioFloor) {
        double low = 0;
        double high = 500_000;

        for (int iter = 0; iter < 40; iter++) {
            double mid = (low + high) / 2;

            double[] testDiscretionary = currentDiscretionary.clone();
            for (int y = phaseStart; y <= phaseEnd; y++) {
                testDiscretionary[y] = mid;
            }

            if (isSustainable(paths, income, floors, testDiscretionary,
                    terminalTarget, years, trialCount, confidenceLevel, portfolioFloor)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return low;
    }

    private boolean isSustainable(double[][] paths, double[] income,
                                   double[] floors, double[] discretionary,
                                   double terminalTarget, int years, int trialCount,
                                   double confidenceLevel, double portfolioFloor) {
        double[] finalBalances = new double[trialCount];
        double[] minBalances = new double[trialCount];

        for (int t = 0; t < trialCount; t++) {
            double balance = paths[t][0];
            double minBalance = balance;
            for (int y = 0; y < years; y++) {
                double growthFactor = paths[t][y + 1] / paths[t][y];
                double spending = floors[y] + discretionary[y];
                double withdrawal = Math.max(0, spending - income[y]);
                balance = balance * growthFactor - withdrawal;
                if (balance < 0) {
                    balance = 0;
                }
                minBalance = Math.min(minBalance, balance);
            }
            finalBalances[t] = balance;
            minBalances[t] = minBalance;
        }

        Arrays.sort(finalBalances);
        double balanceAtConfidence = percentile(finalBalances, 1.0 - confidenceLevel);
        if (balanceAtConfidence < terminalTarget) {
            return false;
        }

        if (portfolioFloor > 0) {
            Arrays.sort(minBalances);
            double minAtConfidence = percentile(minBalances, 1.0 - confidenceLevel);
            if (minAtConfidence < portfolioFloor) {
                return false;
            }
        }

        return true;
    }

    private double[][] computeCorridors(double[][] paths, double[] income,
                                         double[] floors, double[] discretionary,
                                         double terminalTarget, int years, int trialCount) {
        double[] corridorLow = new double[years];
        double[] corridorHigh = new double[years];

        for (int y = 0; y < years; y++) {
            double baseSpending = floors[y] + discretionary[y];

            // Compute what spending is sustainable at different percentiles
            double[] sustainableAtYear = new double[trialCount];
            for (int t = 0; t < trialCount; t++) {
                // For this trial, how much could we spend this year?
                double balance = paths[t][0];
                for (int py = 0; py < y; py++) {
                    double gf = paths[t][py + 1] / paths[t][py];
                    double spending = floors[py] + discretionary[py];
                    double withdrawal = Math.max(0, spending - income[py]);
                    balance = Math.max(0, balance * gf - withdrawal);
                }
                double gf = paths[t][y + 1] / paths[t][y];
                double availableThisYear = balance * gf + income[y];
                sustainableAtYear[t] = availableThisYear;
            }
            Arrays.sort(sustainableAtYear);

            double p10 = percentile(sustainableAtYear, 0.10);
            double p90 = percentile(sustainableAtYear, 0.90);

            // Corridor bounded by floor and some reasonable cap
            corridorLow[y] = Math.max(floors[y], Math.min(p10, baseSpending));
            corridorHigh[y] = Math.max(baseSpending, Math.min(p90, baseSpending * 3));
        }

        return new double[][]{corridorLow, corridorHigh};
    }

    private double[] simulateWithWithdrawals(int trialCount, int years,
                                              double initialPortfolio,
                                              double mu, double sigma, Random rng,
                                              double[] floors, double[] discretionary,
                                              double[] income) {
        double[] finalBalances = new double[trialCount];
        for (int t = 0; t < trialCount; t++) {
            double balance = initialPortfolio;
            for (int y = 0; y < years; y++) {
                double logReturn = mu + sigma * rng.nextGaussian();
                double growthFactor = Math.exp(logReturn);
                double spending = floors[y] + discretionary[y];
                double withdrawal = Math.max(0, spending - income[y]);
                balance = Math.max(0, balance * growthFactor - withdrawal);
            }
            finalBalances[t] = balance;
        }
        return finalBalances;
    }

    private void applyPhaseBlending(double[] discretionary, double[] floors,
                                     List<GuardrailPhaseInput> phases,
                                     int retirementAge, int years, int blendYears) {
        double[] totalSpending = new double[years];
        for (int y = 0; y < years; y++) {
            totalSpending[y] = floors[y] + discretionary[y];
        }

        for (int p = 1; p < phases.size(); p++) {
            int boundaryAge = phases.get(p).startAge();
            int boundaryYear = boundaryAge - retirementAge;
            if (boundaryYear <= 0 || boundaryYear >= years) {
                continue;
            }

            int windowStart = Math.max(0, boundaryYear - blendYears);
            int windowEnd = Math.min(years - 1, boundaryYear + blendYears - 1);
            int windowLen = windowEnd - windowStart + 1;
            if (windowLen <= 1) {
                continue;
            }

            double startSpend = totalSpending[windowStart];
            double endSpend = totalSpending[windowEnd];

            for (int y = windowStart; y <= windowEnd; y++) {
                double t = (double) (y - windowStart) / (windowLen - 1);
                double blended = startSpend + t * (endSpend - startSpend);
                totalSpending[y] = blended;
                discretionary[y] = Math.max(0, blended - floors[y]);
            }
        }
    }

    private void applyYearOverYearSmoothing(double[] discretionary, double[] floors,
                                             double maxRate, int years) {
        double[] totalSpending = new double[years];
        for (int y = 0; y < years; y++) {
            totalSpending[y] = floors[y] + discretionary[y];
        }

        for (int y = 1; y < years; y++) {
            double maxUp = totalSpending[y - 1] * (1 + maxRate);
            double maxDown = totalSpending[y - 1] * (1 - maxRate);
            totalSpending[y] = Math.max(maxDown, Math.min(maxUp, totalSpending[y]));
            discretionary[y] = Math.max(0, totalSpending[y] - floors[y]);
        }
    }

    private void smoothCorridors(double[] corridorLow, double[] corridorHigh, int years) {
        if (years < 3) {
            return;
        }
        double[] smoothLow = new double[years];
        double[] smoothHigh = new double[years];

        smoothLow[0] = corridorLow[0];
        smoothHigh[0] = corridorHigh[0];
        smoothLow[years - 1] = corridorLow[years - 1];
        smoothHigh[years - 1] = corridorHigh[years - 1];

        for (int y = 1; y < years - 1; y++) {
            smoothLow[y] = (corridorLow[y - 1] + corridorLow[y] + corridorLow[y + 1]) / 3.0;
            smoothHigh[y] = (corridorHigh[y - 1] + corridorHigh[y] + corridorHigh[y + 1]) / 3.0;
        }

        System.arraycopy(smoothLow, 0, corridorLow, 0, years);
        System.arraycopy(smoothHigh, 0, corridorHigh, 0, years);

        // Ensure corridorLow <= corridorHigh after smoothing
        for (int y = 0; y < years; y++) {
            if (corridorLow[y] > corridorHigh[y]) {
                double avg = (corridorLow[y] + corridorHigh[y]) / 2.0;
                corridorLow[y] = avg;
                corridorHigh[y] = avg;
            }
        }
    }

    private double totalPortfolio(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }

    private String findPhaseName(List<GuardrailPhaseInput> phases, int age) {
        if (phases == null || phases.isEmpty()) {
            return "Retirement";
        }
        for (var phase : phases) {
            if (age >= phase.startAge()
                    && (phase.endAge() == null || age <= phase.endAge())) {
                return phase.name();
            }
        }
        return "Retirement";
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        double index = p * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = Math.min(lower + 1, sorted.length - 1);
        double fraction = index - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }

    private static BigDecimal toBD(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, ROUNDING);
    }

    private GuardrailProfileResponse emptyResult(GuardrailOptimizationInput input) {
        return new GuardrailProfileResponse(
                null, null, "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                input.returnMean(), input.returnStddev(),
                input.trialCount(), input.confidenceLevel(),
                input.phases(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                false, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
