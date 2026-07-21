import type { Sex } from '../../types/projection';

export const DEFAULT_SURVIVOR_SPENDING_FACTOR = 75;
/** Sub-project B (stochastic mortality): mirrors ScenarioCrudService.MIN/MAX_LONGEVITY_CONDITIONAL_AGE. */
export const MIN_LONGEVITY_CONDITIONAL_AGE = 80;
export const MAX_LONGEVITY_CONDITIONAL_AGE = 110;
export const DEFAULT_LONGEVITY_CONDITIONAL_AGE = 95;

export interface ScenarioFormFields {
    name: string;
    retirementDate: string;
    endAge: number;
    inflationRate: number;
    birthYear: number;
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
