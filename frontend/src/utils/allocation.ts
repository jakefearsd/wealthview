import type { AllocationInput } from '../types/projection';

const ALLOCATION_SUM_TOLERANCE = 0.01;
const ALLOCATION_TARGET_SUM = 100;

export function allocationSum(value: AllocationInput): number {
    return value.us_stock + value.intl_stock + value.bond + value.cash;
}

export function isAllocationValid(value: AllocationInput | null): boolean {
    if (value === null) {
        return true;
    }
    return Math.abs(allocationSum(value) - ALLOCATION_TARGET_SUM) <= ALLOCATION_SUM_TOLERANCE;
}
