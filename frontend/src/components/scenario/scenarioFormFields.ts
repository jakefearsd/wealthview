import type { Sex } from '../../types/projection';

export const DEFAULT_SURVIVOR_SPENDING_FACTOR = 75;
/** Sub-project B (stochastic mortality): mirrors ScenarioCrudService.MIN/MAX_LONGEVITY_CONDITIONAL_AGE. */
export const MIN_LONGEVITY_CONDITIONAL_AGE = 80;
export const MAX_LONGEVITY_CONDITIONAL_AGE = 110;
export const DEFAULT_LONGEVITY_CONDITIONAL_AGE = 95;

/** Phase 1a: month picker options shared by the primary and spouse birth-month selects. */
export const MONTH_OPTIONS: ReadonlyArray<{ value: number; label: string }> = [
    { value: 1, label: 'January' }, { value: 2, label: 'February' }, { value: 3, label: 'March' },
    { value: 4, label: 'April' }, { value: 5, label: 'May' }, { value: 6, label: 'June' },
    { value: 7, label: 'July' }, { value: 8, label: 'August' }, { value: 9, label: 'September' },
    { value: 10, label: 'October' }, { value: 11, label: 'November' }, { value: 12, label: 'December' },
];

export interface ScenarioFormFields {
    name: string;
    retirementDate: string;
    endAge: number;
    inflationRate: number;
    birthYear: number;
    birthMonth: number | null;
    withdrawalRate: number;
    withdrawalStrategy: string;
    dynamicCeiling: number;
    dynamicFloor: number;
    filingStatus: string;
    otherIncome: number;
    annualRothConversion: number;
    rothConversionStrategy: string;
    targetBracketRate: number;
    rothConversionStartYear: number | null;
    withdrawalOrder: string;
    dynamicSequencingBracketRate: number;
    state: string;
    primaryResidencePropertyTax: number;
    primaryResidenceMortgageInterest: number;
    dividendYield: number | null;
    feeRate: number | null;
    interestYield: number | null;
    includeDepressionYears: boolean;
    spendingPlanSelection: string;
    spouseBirthYear: number | null;
    spouseBirthMonth: number | null;
    primaryDeathAge: number | null;
    spouseDeathAge: number | null;
    survivorSpendingFactor: number;
    communityProperty: boolean;
    /** Sub-project B (stochastic mortality): opts the guardrail Monte Carlo optimizer into sampled death ages. */
    stochasticMortality: boolean;
    primarySex: Sex | null;
    spouseSex: Sex | null;
    longevityConditionalAge: number;
}

/** Typed single-field updater shared by ScenarioForm (the state owner) and its section components. */
export type SetScenarioField = <K extends keyof ScenarioFormFields>(key: K, value: ScenarioFormFields[K]) => void;
