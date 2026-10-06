import { describe, it, expect } from 'vitest';
import { annualDebtService, loanTermsOf, monthlyPayment, remainingBalance } from './amortization';
import type { LoanTerms } from './amortization';
import type { Property } from '../types/property';

// $360,000 at 4.25% over 30 years, first payment the month after a 2018-07-01 start.
const LOAN: LoanTerms = {
    loanAmount: 360000,
    annualRate: 0.0425,
    termMonths: 360,
    startDate: '2018-07-01',
};

describe('monthlyPayment', () => {
    it('applies the standard amortization formula', () => {
        expect(monthlyPayment(360000, 0.0425, 360)).toBeCloseTo(1770.98, 2);
    });

    it('rounds to four decimal places like the backend', () => {
        const payment = monthlyPayment(360000, 0.0425, 360);

        expect(payment).toBe(Math.round(payment * 10000) / 10000);
    });

    it('splits the principal evenly over the term at a zero rate', () => {
        expect(monthlyPayment(120000, 0, 120)).toBe(1000);
    });

    it('is zero for a non-positive term', () => {
        expect(monthlyPayment(100000, 0.05, 0)).toBe(0);
    });
});

describe('remainingBalance', () => {
    it('is the full loan before any payment is made', () => {
        expect(remainingBalance(LOAN, 0)).toBe(360000);
    });

    it('is zero once every payment is made', () => {
        expect(remainingBalance(LOAN, 360)).toBe(0);
    });

    it('declines linearly at a zero rate', () => {
        expect(remainingBalance({ ...LOAN, annualRate: 0, loanAmount: 120000, termMonths: 120 }, 30)).toBe(90000);
    });
});

describe('annualDebtService', () => {
    it('charges twelve payments in a full year, with interest at the scheduled share', () => {
        const ds = annualDebtService(LOAN, 2026);

        expect(ds.months).toBe(12);
        expect(ds.payment).toBeCloseTo(1770.98 * 12, 0);
        // 2026 holds payments 90..101; interest is r times the balance before each one.
        const r = 0.0425 / 12;
        let expectedInterest = 0;
        for (let k = 90; k <= 101; k++) {
            expectedInterest += r * remainingBalance(LOAN, k - 1);
        }
        expect(ds.interest).toBeCloseTo(expectedInterest, 6);
        expect(ds.interest).toBeGreaterThan(11000);
        expect(ds.interest).toBeLessThan(ds.payment);
    });

    it('counts only the months after the start in the loan\'s first year', () => {
        const ds = annualDebtService(LOAN, 2018);

        // Payments land in Aug..Dec 2018.
        expect(ds.months).toBe(5);
        expect(ds.payment).toBeCloseTo(monthlyPayment(360000, 0.0425, 360) * 5, 6);
    });

    it('counts only the months up to payoff in the loan\'s last year', () => {
        const ds = annualDebtService(LOAN, 2048);

        // The 360th payment is July 2048.
        expect(ds.months).toBe(7);
    });

    it('is zero after payoff and before the loan starts', () => {
        expect(annualDebtService(LOAN, 2049)).toEqual({ months: 0, payment: 0, interest: 0 });
        expect(annualDebtService(LOAN, 2017)).toEqual({ months: 0, payment: 0, interest: 0 });
    });

    it('charges no interest on a zero-rate loan', () => {
        const ds = annualDebtService({ ...LOAN, annualRate: 0, loanAmount: 120000, termMonths: 120 }, 2020);

        expect(ds).toEqual({ months: 12, payment: 12000, interest: 0 });
    });

    it('is zero for a non-positive term', () => {
        expect(annualDebtService({ ...LOAN, termMonths: 0 }, 2026)).toEqual({ months: 0, payment: 0, interest: 0 });
    });
});

describe('loanTermsOf', () => {
    const property = {
        has_loan_details: true,
        loan_amount: 360000,
        annual_interest_rate: 0.0425,
        loan_term_months: 360,
        loan_start_date: '2018-07-01',
    } as Property;

    it('reads the loan terms of a property with complete loan details', () => {
        expect(loanTermsOf(property)).toEqual(LOAN);
    });

    it('is null without loan details', () => {
        expect(loanTermsOf({ ...property, has_loan_details: false })).toBeNull();
        expect(loanTermsOf(null)).toBeNull();
    });

    it('is null when a loan field is missing despite the flag', () => {
        expect(loanTermsOf({ ...property, loan_start_date: null })).toBeNull();
    });
});
