import type { ProjectionYear } from '../types/projection';

export interface ProjectionCsvOptions {
    hasPoolData: boolean;
    hasSpendingData: boolean;
    hasSurplusReinvested: boolean;
    computeTotalSpending: (y: ProjectionYear) => number | null;
}

export function buildProjectionCsv(yearlyData: ProjectionYear[], options: ProjectionCsvOptions): string {
    const { hasPoolData, hasSpendingData, hasSurplusReinvested, computeTotalSpending } = options;
    const csvHasStateTax = yearlyData.some(y => y.state_tax != null);

    const headers = ['Year', 'Age', 'Start', 'Contributions', 'Growth', 'Withdrawals', 'Income', 'Total Spending', 'End', 'Status'];
    if (hasPoolData) {
        headers.push('Traditional', 'Roth', 'Taxable', 'Conversion', 'Tax');
        headers.push('Trad Growth', 'Roth Growth', 'Taxable Growth',
            'Tax from Taxable', 'Tax from Trad', 'Tax from Roth',
            'WD from Taxable', 'WD from Trad', 'WD from Roth');
    }
    if (csvHasStateTax) {
        headers.push('Federal Tax', 'State Tax', 'SALT', 'Deduction Type');
    }
    if (hasSpendingData) {
        headers.push('Essential', 'Discretionary', 'Net Need', 'Surplus/Deficit');
        if (hasSurplusReinvested) headers.push('Surplus Reinvested');
    }

    const rows = yearlyData.map(y => {
        const vals: (string | number)[] = [
            y.year, y.age, y.start_balance, y.contributions, y.growth, y.withdrawals,
            y.income_streams_total ?? '', computeTotalSpending(y) ?? '',
            y.end_balance, y.retired ? 'Retired' : 'Working',
        ];
        if (hasPoolData) {
            vals.push(
                y.traditional_balance ?? '', y.roth_balance ?? '', y.taxable_balance ?? '',
                y.roth_conversion_amount ?? '', y.tax_liability ?? '',
                y.traditional_growth ?? '', y.roth_growth ?? '', y.taxable_growth ?? '',
                y.tax_paid_from_taxable ?? '', y.tax_paid_from_traditional ?? '', y.tax_paid_from_roth ?? '',
                y.withdrawal_from_taxable ?? '', y.withdrawal_from_traditional ?? '', y.withdrawal_from_roth ?? '',
            );
        }
        if (csvHasStateTax) {
            vals.push(
                y.federal_tax ?? '', y.state_tax ?? '', y.salt_deduction ?? '',
                y.used_itemized_deduction != null ? (y.used_itemized_deduction ? 'Itemized' : 'Standard') : '',
            );
        }
        if (hasSpendingData) {
            vals.push(
                y.essential_expenses ?? '', y.discretionary_after_cuts ?? y.discretionary_expenses ?? '',
                y.net_spending_need ?? '', y.spending_surplus ?? '',
            );
            if (hasSurplusReinvested) vals.push(y.surplus_reinvested ?? '');
        }
        return vals.join(',');
    });

    return [headers.join(','), ...rows].join('\n');
}
