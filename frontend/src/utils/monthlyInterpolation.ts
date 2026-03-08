import type { ProjectionYear, ProjectionMonthPoint } from '../types/projection';

export function interpolateMonthly(data: ProjectionYear[]): ProjectionMonthPoint[] {
    if (data.length === 0) return [];

    const result: ProjectionMonthPoint[] = [];

    for (let yi = 0; yi < data.length; yi++) {
        const row = data[yi];
        const prevRow = yi > 0 ? data[yi - 1] : null;

        const hasPoolData = row.traditional_balance !== null;
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
        let tradBal: number | null = null;
        let rothBal: number | null = null;
        let taxableBal: number | null = null;

        if (hasPoolData) {
            if (prevRow && prevRow.traditional_balance !== null) {
                tradBal = prevRow.traditional_balance;
                rothBal = prevRow.roth_balance!;
                taxableBal = prevRow.taxable_balance!;
            } else {
                // First year: derive starting pools from start_balance proportionally
                // using end-of-year pool ratios
                const endTotal = row.traditional_balance! + row.roth_balance! + row.taxable_balance!;
                if (endTotal > 0 && row.start_balance > 0) {
                    tradBal = row.start_balance * (row.traditional_balance! / endTotal);
                    rothBal = row.start_balance * (row.roth_balance! / endTotal);
                    taxableBal = row.start_balance * (row.taxable_balance! / endTotal);
                } else {
                    tradBal = 0;
                    rothBal = 0;
                    taxableBal = row.start_balance;
                }
            }
        }

        let balance = row.start_balance;

        for (let m = 1; m <= 12; m++) {
            if (m === 1) {
                // January: balance = start_balance (already set)
                result.push(makeMonthPoint(row, m, balance, tradBal, rothBal, taxableBal, null));
                continue;
            }

            if (m < 12) {
                // Months 2-11: apply monthly growth + contributions/withdrawals
                const growthAmt = row.start_balance > 0 ? balance * monthlyGrowthRate : additiveGrowth;
                balance += growthAmt + monthlyContrib - monthlyWithdrawal;

                if (hasPoolData) {
                    tradBal = tradBal! + tradBal! * monthlyGrowthRate;
                    rothBal = rothBal! + rothBal! * monthlyGrowthRate;
                    taxableBal = taxableBal! + taxableBal! * monthlyGrowthRate;
                    // Distribute contributions/withdrawals proportionally
                    const poolTotal = tradBal + rothBal + taxableBal;
                    if (poolTotal > 0) {
                        const netFlow = monthlyContrib - monthlyWithdrawal;
                        tradBal += netFlow * (tradBal / poolTotal);
                        rothBal += netFlow * (rothBal / poolTotal);
                        taxableBal += netFlow * (taxableBal / poolTotal);
                    }
                }

                result.push(makeMonthPoint(row, m, balance, tradBal, rothBal, taxableBal, null));
            } else {
                // Month 12 (December): snap to engine values
                balance = row.end_balance;

                if (hasPoolData) {
                    tradBal = row.traditional_balance!;
                    rothBal = row.roth_balance!;
                    taxableBal = row.taxable_balance!;
                }

                result.push(makeMonthPoint(
                    row, m, balance, tradBal, rothBal, taxableBal,
                    row.roth_conversion_amount,
                ));
            }
        }
    }

    return result;
}

function makeMonthPoint(
    row: ProjectionYear,
    month: number,
    balance: number,
    tradBal: number | null,
    rothBal: number | null,
    taxableBal: number | null,
    rothConversion: number | null,
): ProjectionMonthPoint {
    const label = `${row.year}-${String(month).padStart(2, '0')}`;
    return {
        label,
        year: row.year,
        month,
        age: row.age,
        balance,
        traditional_balance: tradBal,
        roth_balance: rothBal,
        taxable_balance: taxableBal,
        retired: row.retired,
        roth_conversion_amount: rothConversion,
    };
}
