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

        // Stage 3: Priority-weighted discretionary allocation
        double[] discretionaryByYear = allocateSpending(
                portfolioPaths, incomeByYear, adjustedFloors, terminalTarget,
                input.phases(), retirementAge, years, trialCount);

        // Compute corridors
        double[][] corridors = computeCorridors(
                portfolioPaths, incomeByYear, adjustedFloors, discretionaryByYear,
                terminalTarget, years, trialCount);

        // Compute final balance statistics
        double[] finalBalances = new double[trialCount];
        for (int t = 0; t < trialCount; t++) {
            double balance = portfolioPaths[t][years];
            double totalWithdrawn = 0;
            for (int y = 0; y < years; y++) {
                double spending = adjustedFloors[y] + discretionaryByYear[y];
                double withdrawal = Math.max(0, spending - incomeByYear[y]);
                totalWithdrawn += withdrawal;
            }
            finalBalances[t] = Math.max(0, portfolioPaths[t][years] - totalWithdrawn);
        }

        // Re-simulate final balances with actual withdrawals
        finalBalances = simulateWithWithdrawals(
                trialCount, years, initialPortfolio, mu, sigma,
                input.seed() != null ? new Random(input.seed()) : rng,
                adjustedFloors, discretionaryByYear, incomeByYear);

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
                    toBD(floor), toBD(disc), toBD(income), toBD(withdrawal), phaseName));
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
                                       int retirementAge, int years, int trialCount) {
        double[] discretionary = new double[years];

        if (phases == null || phases.isEmpty()) {
            // Single phase: binary search for uniform discretionary
            double maxDisc = binarySearchDiscretionary(
                    paths, income, floors, discretionary, terminalTarget,
                    0, years - 1, years, trialCount);
            Arrays.fill(discretionary, maxDisc);
            return discretionary;
        }

        // Sort phases by priority weight (highest first)
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
                    phaseStart, phaseEnd, years, trialCount);

            for (int y = phaseStart; y <= phaseEnd; y++) {
                discretionary[y] = maxDisc;
            }
        }

        return discretionary;
    }

    private double binarySearchDiscretionary(double[][] paths, double[] income,
                                              double[] floors, double[] currentDiscretionary,
                                              double terminalTarget,
                                              int phaseStart, int phaseEnd,
                                              int years, int trialCount) {
        double low = 0;
        double high = 500_000; // reasonable upper bound for annual discretionary

        for (int iter = 0; iter < 40; iter++) {
            double mid = (low + high) / 2;

            double[] testDiscretionary = currentDiscretionary.clone();
            for (int y = phaseStart; y <= phaseEnd; y++) {
                testDiscretionary[y] = mid;
            }

            if (isSustainable(paths, income, floors, testDiscretionary,
                    terminalTarget, years, trialCount)) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return low;
    }

    private boolean isSustainable(double[][] paths, double[] income,
                                   double[] floors, double[] discretionary,
                                   double terminalTarget, int years, int trialCount) {
        // Check at 50th percentile (median)
        double[] finalBalances = new double[trialCount];
        for (int t = 0; t < trialCount; t++) {
            double balance = paths[t][0];
            for (int y = 0; y < years; y++) {
                double growthFactor = paths[t][y + 1] / paths[t][y];
                double spending = floors[y] + discretionary[y];
                double withdrawal = Math.max(0, spending - income[y]);
                balance = balance * growthFactor - withdrawal;
                if (balance < 0) {
                    balance = 0;
                }
            }
            finalBalances[t] = balance;
        }
        Arrays.sort(finalBalances);
        double medianBalance = percentile(finalBalances, 0.50);
        return medianBalance >= terminalTarget;
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
