import type { ProjectionYear, ProjectionMonthPoint } from '../types/projection';

/**
 * The three retirement pools, all present together. The engine either reports every pool balance
 * for a year or none of them, so this is read all-or-nothing via {@link readPools} — that invariant
 * used to be spelled as `!` non-null assertions at each use site.
 */
interface Pools {
    trad: number;
    roth: number;
    taxable: number;
}

/** The year's end-of-year pool balances, or null when this row carries no pool detail. */
function readPools(row: ProjectionYear): Pools | null {
    if (
        row.traditional_balance === null
        || row.roth_balance === null
        || row.taxable_balance === null
    ) {
        return null;
    }
    return {
        trad: row.traditional_balance,
        roth: row.roth_balance,
        taxable: row.taxable_balance,
    };
}

function poolTotal(pools: Pools): number {
    return pools.trad + pools.roth + pools.taxable;
}

function scalePools(pools: Pools, factor: number): Pools {
    return {
        trad: pools.trad * factor,
        roth: pools.roth * factor,
        taxable: pools.taxable * factor,
    };
}

function growPools(pools: Pools, monthlyGrowthRate: number): Pools {
    return {
        trad: pools.trad + pools.trad * monthlyGrowthRate,
        roth: pools.roth + pools.roth * monthlyGrowthRate,
        taxable: pools.taxable + pools.taxable * monthlyGrowthRate,
    };
}

/** Distributes a month's net contribution/withdrawal across the pools in proportion to their size. */
function applyNetFlow(pools: Pools, netFlow: number): Pools {
    const total = poolTotal(pools);
    if (total <= 0) {
        return pools;
    }
    return {
        trad: pools.trad + netFlow * (pools.trad / total),
        roth: pools.roth + netFlow * (pools.roth / total),
        taxable: pools.taxable + netFlow * (pools.taxable / total),
    };
}

export function interpolateMonthly(data: ProjectionYear[]): ProjectionMonthPoint[] {
    if (data.length === 0) return [];

    const result: ProjectionMonthPoint[] = [];

    for (let yi = 0; yi < data.length; yi++) {
        const row = data[yi];
        const prevRow = yi > 0 ? data[yi - 1] : null;

        const endPools = readPools(row);
        const monthlyContrib = row.contributions / 12;
        const monthlyWithdrawal = row.withdrawals / 12;

        // Compute monthly growth rate
        let monthlyGrowthRate = 0;
        if (row.start_balance > 0) {
            const annualGrowthRate = row.growth / row.start_balance;
            monthlyGrowthRate = Math.pow(1 + annualGrowthRate, 1 / 12) - 1;
        }
        const additiveGrowth = row.start_balance === 0 ? row.growth / 12 : 0;

        // Starting pool balances for this year
        let pools: Pools | null = null;
        if (endPools) {
            const prevPools = prevRow ? readPools(prevRow) : null;
            if (prevPools) {
                pools = prevPools;
            } else {
                // First year: derive starting pools from start_balance proportionally
                // using end-of-year pool ratios
                const endTotal = poolTotal(endPools);
                pools = endTotal > 0 && row.start_balance > 0
                    ? scalePools(endPools, row.start_balance / endTotal)
                    : { trad: 0, roth: 0, taxable: row.start_balance };
            }
        }

        let balance = row.start_balance;

        for (let m = 1; m <= 12; m++) {
            if (m === 1) {
                // January: balance = start_balance (already set)
                result.push(makeMonthPoint(row, m, balance, pools, null));
                continue;
            }

            if (m < 12) {
                // Months 2-11: apply monthly growth + contributions/withdrawals
                const growthAmt = row.start_balance > 0 ? balance * monthlyGrowthRate : additiveGrowth;
                balance += growthAmt + monthlyContrib - monthlyWithdrawal;

                if (pools) {
                    pools = applyNetFlow(
                        growPools(pools, monthlyGrowthRate),
                        monthlyContrib - monthlyWithdrawal,
                    );
                }

                result.push(makeMonthPoint(row, m, balance, pools, null));
            } else {
                // Month 12 (December): snap to engine values
                balance = row.end_balance;

                if (endPools) {
                    pools = endPools;
                }

                result.push(makeMonthPoint(row, m, balance, pools, row.roth_conversion_amount));
            }
        }
    }

    return result;
}

function makeMonthPoint(
    row: ProjectionYear,
    month: number,
    balance: number,
    pools: Pools | null,
    rothConversion: number | null,
): ProjectionMonthPoint {
    const label = `${row.year}-${String(month).padStart(2, '0')}`;
    return {
        label,
        year: row.year,
        month,
        age: row.age,
        balance,
        traditional_balance: pools ? pools.trad : null,
        roth_balance: pools ? pools.roth : null,
        taxable_balance: pools ? pools.taxable : null,
        retired: row.retired,
        roth_conversion_amount: rothConversion,
    };
}
