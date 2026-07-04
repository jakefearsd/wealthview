import type { ProjectionYear, SpendingFeasibility } from '../types/projection';
import { formatCurrency } from './format';

export function findPeakBalance(data: ProjectionYear[]): { year: number; balance: number } {
    if (data.length === 0) return { year: 0, balance: 0 };
    let peak = data[0];
    for (const row of data) {
        if (row.end_balance > peak.end_balance) peak = row;
    }
    return { year: peak.year, balance: peak.end_balance };
}

export function findDepletionYear(data: ProjectionYear[]): { year: number; age: number } | null {
    for (const row of data) {
        if (row.end_balance <= 0) return { year: row.year, age: row.age };
    }
    return null;
}

export function findCrossoverYear(data: ProjectionYear[]): { year: number; age: number } | null {
    for (const row of data) {
        if (row.retired && row.essential_expenses != null
            && row.withdrawals > row.growth && row.end_balance > 0) {
            return { year: row.year, age: row.age };
        }
    }
    return null;
}

export function computeCumulativeContributions(data: ProjectionYear[]): number[] {
    const result: number[] = [];
    let sum = 0;
    for (const row of data) {
        sum += row.contributions;
        result.push(sum);
    }
    return result;
}

export interface PropertyTaxShield {
    name: string;
    taxTreatment: string;
    depreciation: number;
    lossApplied: number;
}

export interface TaxShieldSummary {
    totalDepreciation: number;
    totalLossApplied: number;
    estimatedTaxSavings: number;
    rothConversionSheltered: number;
    suspendedLossRemaining: number;
    perProperty: PropertyTaxShield[];
}

/**
 * Aggregates the depreciation tax shield over the retired years: total
 * depreciation taken, rental losses applied to income, approximate tax
 * savings (effective-rate estimate), the loss amount sheltering Roth
 * conversions, and per-property breakdowns.
 */
export function computeTaxShieldSummary(data: ProjectionYear[]): TaxShieldSummary {
    const years = data.filter(y => y.retired);

    let totalDepreciation = 0;
    let totalLossApplied = 0;
    let estimatedTaxSavings = 0;
    let rothConversionSheltered = 0;
    const perProperty: Record<string, PropertyTaxShield> = {};

    for (const y of years) {
        const dep = y.depreciation_total || 0;
        const loss = y.rental_loss_applied || 0;
        totalDepreciation += dep;
        totalLossApplied += loss;

        // Estimated tax savings using effective rate (approximate)
        if (loss > 0 && y.tax_liability != null && y.tax_liability > 0) {
            const taxableIncome = (y.income_streams_total || 0) + (y.roth_conversion_amount || 0);
            const effectiveRate = taxableIncome > 0 ? y.tax_liability / taxableIncome : 0;
            estimatedTaxSavings += loss * effectiveRate;
        }

        // Roth conversion sheltered (approximate)
        if (loss > 0 && y.roth_conversion_amount && y.roth_conversion_amount > 0) {
            rothConversionSheltered += Math.min(loss, y.roth_conversion_amount);
        }

        // Per-property aggregation
        if (y.rental_property_details) {
            for (const d of y.rental_property_details) {
                const key = d.income_source_id;
                if (!perProperty[key]) {
                    perProperty[key] = { name: d.property_name, taxTreatment: d.tax_treatment, depreciation: 0, lossApplied: 0 };
                }
                perProperty[key].depreciation += d.depreciation;
                perProperty[key].lossApplied += d.loss_applied_to_income;
            }
        }
    }

    const suspendedLossRemaining = years.length > 0
        ? (years[years.length - 1].suspended_loss_carryforward || 0)
        : 0;

    return {
        totalDepreciation, totalLossApplied, estimatedTaxSavings,
        rothConversionSheltered, suspendedLossRemaining,
        perProperty: Object.values(perProperty),
    };
}

export interface TaxMetrics {
    lifetimeTax: number;
    avgRate: number;
    totalStateTax: number;
    totalSalt: number;
    itemizedCount: number;
    totalRetiredYears: number;
    hasStateTax: boolean;
}

/**
 * Summarizes retirement tax burden across the retired years that have a
 * computed tax liability: lifetime tax, average effective rate, state tax
 * and SALT totals. Returns null when the projection carries no tax data.
 */
export function computeTaxMetrics(data: ProjectionYear[]): TaxMetrics | null {
    const retiredYears = data.filter(y => y.retired && y.tax_liability != null);
    if (retiredYears.length === 0) return null;

    const hasStateTax = retiredYears.some(y => y.state_tax != null);

    let lifetimeTax = 0;
    let totalStateTax = 0;
    let totalSalt = 0;
    let itemizedCount = 0;
    const rates: number[] = [];

    for (const y of retiredYears) {
        lifetimeTax += y.tax_liability ?? 0;
        totalStateTax += y.state_tax ?? 0;
        if (y.used_itemized_deduction) {
            totalSalt += y.salt_deduction ?? 0;
            itemizedCount++;
        }

        const taxableIncome = (y.income_streams_total ?? 0)
            + (y.roth_conversion_amount ?? 0)
            + (y.withdrawal_from_traditional ?? 0);
        if (taxableIncome > 0) {
            rates.push(((y.tax_liability ?? 0) / taxableIncome) * 100);
        }
    }

    const avgRate = rates.length > 0 ? rates.reduce((a, b) => a + b, 0) / rates.length : 0;

    return {
        lifetimeTax,
        avgRate: Math.round(avgRate * 10) / 10,
        totalStateTax,
        totalSalt,
        itemizedCount,
        totalRetiredYears: retiredYears.length,
        hasStateTax,
    };
}

/**
 * Total spending for one projection year. For retired years, withdrawals +
 * income reflects actual spending (including guardrail-recommended amounts).
 * Profile-derived essential + discretionary may show the spending PROFILE's
 * target, which can differ from the optimizer output.
 */
export function computeTotalSpending(y: ProjectionYear): number | null {
    if (y.retired && (y.withdrawals > 0 || (y.income_streams_total != null && y.income_streams_total > 0))) {
        return y.withdrawals + (y.income_streams_total ?? 0);
    }
    if (y.essential_expenses != null) {
        return y.essential_expenses + (y.discretionary_after_cuts ?? y.discretionary_expenses ?? 0);
    }
    return null;
}

export interface PlanOutcome {
    label: string;
    value: string;
    color: string;
    description: string;
}

/**
 * Derives the summary-card copy for the plan outcome: with no spending
 * profile it reports the raw depletion year; with a profile it reports
 * sustainability against the required spending.
 */
export function computePlanOutcome(
    feasibility: SpendingFeasibility | null,
    depletion: { year: number; age: number } | null,
): PlanOutcome {
    if (!feasibility) {
        const depletes = depletion !== null;
        return {
            label: 'Depletion Year',
            value: depletes ? `${depletion.year} (age ${depletion.age})` : 'Never',
            color: depletes ? '#d32f2f' : '#2e7d32',
            description: depletes
                ? 'The year your portfolio reaches $0.'
                : 'Your money outlasts the plan.',
        };
    }
    if (depletion !== null) {
        return {
            label: 'Plan Outcome',
            value: `Depleted at age ${depletion.age}`,
            color: '#d32f2f',
            description: `Portfolio reaches $0 in ${depletion.year}.`,
        };
    }
    if (feasibility.spending_feasible) {
        return {
            label: 'Plan Outcome',
            value: 'Fully Sustainable',
            color: '#2e7d32',
            description: `Tightest year: ${formatCurrency(feasibility.sustainable_annual_spending)}/yr available vs ${formatCurrency(feasibility.required_annual_spending)}/yr needed`,
        };
    }
    return {
        label: 'Plan Outcome',
        value: `Underfunded at age ${feasibility.first_shortfall_age}`,
        color: '#d32f2f',
        description: `Sustains ${formatCurrency(feasibility.sustainable_annual_spending)}/yr of ${formatCurrency(feasibility.required_annual_spending)}/yr needed`,
    };
}
