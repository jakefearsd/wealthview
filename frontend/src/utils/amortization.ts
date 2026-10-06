import type { Property } from '../types/property';

/**
 * Mortgage amortization, mirroring the backend's AmortizationCalculator
 * (wealthview-core/.../property/AmortizationCalculator.java) so the forward cash-flow
 * chart and the server-side Investment Metrics agree on the payment and the schedule.
 *
 * Payment timing follows the backend's balance math: payment k (1..termMonths) falls in
 * the k-th calendar month after the loan's start month, so a loan starting in July 2018
 * makes its first payment in August 2018.
 */

export interface LoanTerms {
    loanAmount: number;
    /** Annual rate as a decimal, e.g. 0.0425 for 4.25%. */
    annualRate: number;
    termMonths: number;
    /** ISO date, YYYY-MM-DD. */
    startDate: string;
}

export interface AnnualDebtService {
    /** Number of payments that fall in the year. */
    months: number;
    /** Total principal and interest paid in the year (nominal, not inflated). */
    payment: number;
    /** The interest portion of {@link payment} — the deductible part. */
    interest: number;
}

const NO_DEBT_SERVICE: AnnualDebtService = { months: 0, payment: 0, interest: 0 };

function round4(value: number): number {
    return Math.round(value * 10000) / 10000;
}

/** M = P * r(1+r)^n / ((1+r)^n - 1); P / n at a zero rate; 0 for a non-positive term. */
export function monthlyPayment(loanAmount: number, annualRate: number, termMonths: number): number {
    if (termMonths <= 0) return 0;
    if (annualRate === 0) return round4(loanAmount / termMonths);
    const r = annualRate / 12;
    const growth = Math.pow(1 + r, termMonths);
    return round4(loanAmount * (r * growth) / (growth - 1));
}

/** B = P * [(1+r)^n - (1+r)^p] / [(1+r)^n - 1] after {@code paymentsMade} payments; never negative. */
export function remainingBalance(terms: LoanTerms, paymentsMade: number): number {
    const { loanAmount, annualRate, termMonths } = terms;
    if (termMonths <= 0) return 0;
    if (paymentsMade <= 0) return loanAmount;
    if (paymentsMade >= termMonths) return 0;
    if (annualRate === 0) {
        return Math.max(0, loanAmount - (loanAmount / termMonths) * paymentsMade);
    }
    const r = annualRate / 12;
    const growthN = Math.pow(1 + r, termMonths);
    const growthP = Math.pow(1 + r, paymentsMade);
    return Math.max(0, round4(loanAmount * (growthN - growthP) / (growthN - 1)));
}

function monthIndex(isoDate: string): number {
    const [year, month] = isoDate.split('-').map(Number);
    return year * 12 + (month - 1);
}

/**
 * Principal-and-interest paid in one calendar year. A partial first or last year counts
 * only the payments that fall in it; years before the first payment or after payoff are zero.
 */
export function annualDebtService(terms: LoanTerms, year: number): AnnualDebtService {
    if (terms.termMonths <= 0) return NO_DEBT_SERVICE;

    const start = monthIndex(terms.startDate);
    const firstPayment = Math.max(1, year * 12 - start);
    const lastPayment = Math.min(terms.termMonths, year * 12 + 11 - start);
    if (lastPayment < firstPayment) return NO_DEBT_SERVICE;

    const months = lastPayment - firstPayment + 1;
    const payment = monthlyPayment(terms.loanAmount, terms.annualRate, terms.termMonths) * months;
    const r = terms.annualRate / 12;
    let interest = 0;
    for (let k = firstPayment; k <= lastPayment; k++) {
        interest += r * remainingBalance(terms, k - 1);
    }
    return { months, payment, interest };
}

/** The amortizable loan terms of a property, or null when its loan details are incomplete. */
export function loanTermsOf(property: Property | null): LoanTerms | null {
    if (!property?.has_loan_details) return null;
    const { loan_amount, annual_interest_rate, loan_term_months, loan_start_date } = property;
    if (loan_amount == null || annual_interest_rate == null || loan_term_months == null || loan_start_date == null) {
        return null;
    }
    return {
        loanAmount: loan_amount,
        annualRate: annual_interest_rate,
        termMonths: loan_term_months,
        startDate: loan_start_date,
    };
}
