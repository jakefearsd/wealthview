import { describe, it, expect } from 'vitest';
import { buildProjectionCsv } from './projectionCsv';
import type { ProjectionYear } from '../types/projection';

function makeYear(overrides: Partial<ProjectionYear> = {}): ProjectionYear {
    return {
        year: 2030,
        age: 65,
        start_balance: 1000000,
        contributions: 0,
        growth: 70000,
        withdrawals: 40000,
        end_balance: 1030000,
        retired: true,
        traditional_balance: null,
        roth_balance: null,
        taxable_balance: null,
        roth_conversion_amount: null,
        tax_liability: null,
        essential_expenses: null,
        discretionary_expenses: null,
        income_streams_total: null,
        net_spending_need: null,
        spending_surplus: null,
        discretionary_after_cuts: null,
        rental_income_gross: null,
        rental_expenses_total: null,
        depreciation_total: null,
        rental_loss_applied: null,
        suspended_loss_carryforward: null,
        social_security_taxable: null,
        self_employment_tax: null,
        rental_property_details: null,
        income_by_source: null,
        property_equity: null,
        total_net_worth: null,
        surplus_reinvested: null,
        taxable_growth: null,
        traditional_growth: null,
        roth_growth: null,
        tax_paid_from_taxable: null,
        tax_paid_from_traditional: null,
        tax_paid_from_roth: null,
        withdrawal_from_taxable: null,
        withdrawal_from_traditional: null,
        withdrawal_from_roth: null,
        federal_tax: null,
        state_tax: null,
        salt_deduction: null,
        used_itemized_deduction: null,
        ...overrides,
    };
}

const baseOptions = {
    hasPoolData: false,
    hasSpendingData: false,
    hasSurplusReinvested: false,
    computeTotalSpending: () => null,
};

describe('buildProjectionCsv', () => {
    it('emits the core header row with no pool or spending data', () => {
        const csv = buildProjectionCsv([], baseOptions);

        expect(csv).toBe('Year,Age,Start,Contributions,Growth,Withdrawals,Income,Total Spending,End,Status');
    });

    it('renders a single year row with retired status', () => {
        const csv = buildProjectionCsv([makeYear()], baseOptions);

        const [, row] = csv.split('\n');
        expect(row).toBe('2030,65,1000000,0,70000,40000,,,1030000,Retired');
    });

    it('marks working years as Working in the status column', () => {
        const csv = buildProjectionCsv([makeYear({ retired: false })], baseOptions);

        expect(csv.split('\n')[1]).toContain(',Working');
    });

    it('writes income_streams_total and computeTotalSpending when provided', () => {
        const csv = buildProjectionCsv(
            [makeYear({ income_streams_total: 20000 })],
            { ...baseOptions, computeTotalSpending: () => 55000 },
        );

        expect(csv.split('\n')[1]).toContain(',20000,55000,');
    });

    it('emits empty strings for null income and missing total spending', () => {
        const csv = buildProjectionCsv([makeYear()], baseOptions);

        // Income and Total Spending columns come back empty
        expect(csv.split('\n')[1]).toContain(',,,1030000,');
    });

    it('adds pool columns and values when hasPoolData is true', () => {
        const csv = buildProjectionCsv(
            [
                makeYear({
                    traditional_balance: 500000,
                    roth_balance: 300000,
                    taxable_balance: 200000,
                    roth_conversion_amount: 10000,
                    tax_liability: 5000,
                    traditional_growth: 35000,
                    roth_growth: 21000,
                    taxable_growth: 14000,
                    tax_paid_from_taxable: 2000,
                    tax_paid_from_traditional: 2500,
                    tax_paid_from_roth: 500,
                    withdrawal_from_taxable: 10000,
                    withdrawal_from_traditional: 20000,
                    withdrawal_from_roth: 10000,
                }),
            ],
            { ...baseOptions, hasPoolData: true },
        );

        const [header, row] = csv.split('\n');
        expect(header).toContain(',Traditional,Roth,Taxable,Conversion,Tax');
        expect(header).toContain(',Trad Growth,Roth Growth,Taxable Growth');
        expect(header).toContain(',Tax from Taxable,Tax from Trad,Tax from Roth');
        expect(header).toContain(',WD from Taxable,WD from Trad,WD from Roth');
        expect(row).toContain(',500000,300000,200000,10000,5000,');
        expect(row).toContain(',35000,21000,14000,');
        expect(row).toContain(',2000,2500,500,');
        expect(row).toContain(',10000,20000,10000');
    });

    it('writes empty strings for null pool values', () => {
        const csv = buildProjectionCsv([makeYear()], { ...baseOptions, hasPoolData: true });

        const cells = csv.split('\n')[1].split(',');
        // After the 10 base columns (Year..Status), 14 pool columns all empty
        expect(cells.slice(10)).toEqual(Array(14).fill(''));
    });

    it('adds state-tax columns only when any row has state_tax', () => {
        const csv = buildProjectionCsv(
            [
                makeYear({
                    federal_tax: 12000,
                    state_tax: 4000,
                    salt_deduction: 10000,
                    used_itemized_deduction: true,
                }),
            ],
            baseOptions,
        );

        const [header, row] = csv.split('\n');
        expect(header).toContain(',Federal Tax,State Tax,SALT,Deduction Type');
        expect(row).toContain(',12000,4000,10000,Itemized');
    });

    it('uses Standard label when used_itemized_deduction is false', () => {
        const csv = buildProjectionCsv(
            [makeYear({ state_tax: 100, used_itemized_deduction: false })],
            baseOptions,
        );

        expect(csv.split('\n')[1]).toContain(',Standard');
    });

    it('leaves deduction-type empty when used_itemized_deduction is null but state_tax present', () => {
        const csv = buildProjectionCsv(
            [makeYear({ state_tax: 100, federal_tax: 500, salt_deduction: 0 })],
            baseOptions,
        );

        const cells = csv.split('\n')[1].split(',');
        // Last cell should be empty (deduction type)
        expect(cells[cells.length - 1]).toBe('');
    });

    it('omits state-tax columns entirely when no year has state_tax', () => {
        const csv = buildProjectionCsv([makeYear({ federal_tax: 500 })], baseOptions);

        expect(csv).not.toContain('Federal Tax');
        expect(csv).not.toContain('State Tax');
    });

    it('adds spending columns when hasSpendingData is true', () => {
        const csv = buildProjectionCsv(
            [
                makeYear({
                    essential_expenses: 40000,
                    discretionary_expenses: 20000,
                    net_spending_need: 50000,
                    spending_surplus: 10000,
                }),
            ],
            { ...baseOptions, hasSpendingData: true },
        );

        const [header, row] = csv.split('\n');
        expect(header).toContain(',Essential,Discretionary,Net Need,Surplus/Deficit');
        expect(row).toContain(',40000,20000,50000,10000');
    });

    it('prefers discretionary_after_cuts over discretionary_expenses in spending output', () => {
        const csv = buildProjectionCsv(
            [
                makeYear({
                    essential_expenses: 40000,
                    discretionary_expenses: 20000,
                    discretionary_after_cuts: 15000,
                }),
            ],
            { ...baseOptions, hasSpendingData: true },
        );

        expect(csv.split('\n')[1]).toContain(',40000,15000,');
    });

    it('adds Surplus Reinvested column when both flags are set', () => {
        const csv = buildProjectionCsv(
            [makeYear({ surplus_reinvested: 7000 })],
            { ...baseOptions, hasSpendingData: true, hasSurplusReinvested: true },
        );

        const [header, row] = csv.split('\n');
        expect(header).toContain(',Surplus Reinvested');
        expect(row.split(',').at(-1)).toBe('7000');
    });

    it('skips Surplus Reinvested column when hasSpendingData is false', () => {
        const csv = buildProjectionCsv(
            [makeYear({ surplus_reinvested: 7000 })],
            { ...baseOptions, hasSurplusReinvested: true },
        );

        expect(csv).not.toContain('Surplus Reinvested');
    });

    it('emits all three section groups together in the expected order', () => {
        const csv = buildProjectionCsv(
            [
                makeYear({
                    traditional_balance: 1,
                    state_tax: 1,
                    essential_expenses: 1,
                    surplus_reinvested: 1,
                }),
            ],
            {
                hasPoolData: true,
                hasSpendingData: true,
                hasSurplusReinvested: true,
                computeTotalSpending: () => 1,
            },
        );

        const header = csv.split('\n')[0];
        const idxStatus = header.indexOf('Status');
        const idxTraditional = header.indexOf('Traditional');
        const idxFederalTax = header.indexOf('Federal Tax');
        const idxEssential = header.indexOf('Essential');
        const idxSurplus = header.indexOf('Surplus Reinvested');

        expect(idxStatus).toBeLessThan(idxTraditional);
        expect(idxTraditional).toBeLessThan(idxFederalTax);
        expect(idxFederalTax).toBeLessThan(idxEssential);
        expect(idxEssential).toBeLessThan(idxSurplus);
    });

    it('produces one newline per row', () => {
        const csv = buildProjectionCsv(
            [makeYear({ year: 2030 }), makeYear({ year: 2031 }), makeYear({ year: 2032 })],
            baseOptions,
        );

        expect(csv.split('\n')).toHaveLength(4); // 1 header + 3 rows
    });
});
