import type { ProjectionYear } from '../types/projection';

export interface ProjectionCsvOptions {
    hasPoolData: boolean;
    hasSpendingData: boolean;
    hasSurplusReinvested: boolean;
    computeTotalSpending: (y: ProjectionYear) => number | null;
}

interface CsvColumn {
    header: string;
    value: (y: ProjectionYear) => string | number;
}

/**
 * Each header and its row extractor live in one spec entry, so a column can never
 * be added or reordered in the header list without its value moving in lockstep.
 */
function buildColumns(yearlyData: ProjectionYear[], options: ProjectionCsvOptions): CsvColumn[] {
    const { hasPoolData, hasSpendingData, hasSurplusReinvested, computeTotalSpending } = options;
    const csvHasStateTax = yearlyData.some(y => y.state_tax != null);

    const columns: CsvColumn[] = [
        { header: 'Year', value: y => y.year },
        { header: 'Age', value: y => y.age },
        { header: 'Start', value: y => y.start_balance },
        { header: 'Contributions', value: y => y.contributions },
        { header: 'Growth', value: y => y.growth },
        { header: 'Withdrawals', value: y => y.withdrawals },
        { header: 'Income', value: y => y.income_streams_total ?? '' },
        { header: 'Total Spending', value: y => computeTotalSpending(y) ?? '' },
        { header: 'End', value: y => y.end_balance },
        { header: 'Status', value: y => (y.retired ? 'Retired' : 'Working') },
    ];
    if (hasPoolData) {
        columns.push(
            { header: 'Traditional', value: y => y.traditional_balance ?? '' },
            { header: 'Roth', value: y => y.roth_balance ?? '' },
            { header: 'Taxable', value: y => y.taxable_balance ?? '' },
            { header: 'Conversion', value: y => y.roth_conversion_amount ?? '' },
            { header: 'Tax', value: y => y.tax_liability ?? '' },
            { header: 'Trad Growth', value: y => y.traditional_growth ?? '' },
            { header: 'Roth Growth', value: y => y.roth_growth ?? '' },
            { header: 'Taxable Growth', value: y => y.taxable_growth ?? '' },
            { header: 'Tax from Taxable', value: y => y.tax_paid_from_taxable ?? '' },
            { header: 'Tax from Trad', value: y => y.tax_paid_from_traditional ?? '' },
            { header: 'Tax from Roth', value: y => y.tax_paid_from_roth ?? '' },
            { header: 'WD from Taxable', value: y => y.withdrawal_from_taxable ?? '' },
            { header: 'WD from Trad', value: y => y.withdrawal_from_traditional ?? '' },
            { header: 'WD from Roth', value: y => y.withdrawal_from_roth ?? '' },
        );
    }
    if (csvHasStateTax) {
        columns.push(
            { header: 'Federal Tax', value: y => y.federal_tax ?? '' },
            { header: 'State Tax', value: y => y.state_tax ?? '' },
            { header: 'SALT', value: y => y.salt_deduction ?? '' },
            {
                header: 'Deduction Type',
                value: y => (y.used_itemized_deduction != null
                    ? (y.used_itemized_deduction ? 'Itemized' : 'Standard')
                    : ''),
            },
        );
    }
    if (hasSpendingData) {
        columns.push(
            { header: 'Essential', value: y => y.essential_expenses ?? '' },
            { header: 'Discretionary', value: y => y.discretionary_after_cuts ?? y.discretionary_expenses ?? '' },
            { header: 'Net Need', value: y => y.net_spending_need ?? '' },
            { header: 'Surplus/Deficit', value: y => y.spending_surplus ?? '' },
        );
        if (hasSurplusReinvested) {
            columns.push({ header: 'Surplus Reinvested', value: y => y.surplus_reinvested ?? '' });
        }
    }
    return columns;
}

export function buildProjectionCsv(yearlyData: ProjectionYear[], options: ProjectionCsvOptions): string {
    const columns = buildColumns(yearlyData, options);
    const headerLine = columns.map(c => c.header).join(',');
    const rows = yearlyData.map(y => columns.map(c => c.value(y)).join(','));
    return [headerLine, ...rows].join('\n');
}
