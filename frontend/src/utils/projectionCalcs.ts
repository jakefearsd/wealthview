import type { ProjectionYear } from '../types/projection';

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

export function computeCumulativeContributions(data: ProjectionYear[]): number[] {
    const result: number[] = [];
    let sum = 0;
    for (const row of data) {
        sum += row.contributions;
        result.push(sum);
    }
    return result;
}
