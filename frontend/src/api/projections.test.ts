import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    del: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
        post: mocks.post,
        put: mocks.put,
        delete: mocks.del,
    },
}));

import {
    listScenarios,
    getScenario,
    createScenario,
    updateScenario,
    deleteScenario,
    runProjection,
    compareScenarios,
    optimizeSpending,
    getGuardrailProfile,
    deleteGuardrailProfile,
    reoptimize,
} from './projections';
import type {
    Scenario,
    ProjectionResult,
    CreateScenarioRequest,
    CompareResponse,
    GuardrailProfileResponse,
    GuardrailOptimizationRequest,
} from '../types/projection';

// Minimal fixtures: projections.ts is a thin Axios wrapper, so the exact
// shape of these large nested types is not what the wrapper logic exercises.
const SCENARIO = { id: 's1', name: 'Base case' } as unknown as Scenario;
const RESULT = {
    scenario_id: 's1',
    yearly_data: [],
    final_balance: 1000000,
    years_in_retirement: 25,
    spending_feasibility: null,
    unclassified_symbols: null,
    final_net_worth: null,
    warnings: null,
} as ProjectionResult;
const COMPARE = { results: [RESULT] } as CompareResponse;
const GUARDRAIL = { id: 'g1', scenario_id: 's1' } as unknown as GuardrailProfileResponse;

const CREATE: CreateScenarioRequest = {
    name: 'Base case',
    retirement_date: '2040-01-01',
    end_age: 95,
    inflation_rate: 0.03,
    birth_year: 1985,
    withdrawal_rate: 0.04,
    accounts: [],
};

const OPTIMIZE = { confidence_level: 0.85 } as unknown as GuardrailOptimizationRequest;

describe('api/projections', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
        mocks.put.mockReset();
        mocks.del.mockReset();
    });

    it('listScenarios returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [SCENARIO] });

        const result = await listScenarios();

        expect(result).toEqual([SCENARIO]);
        expect(mocks.get).toHaveBeenCalledWith('/projections');
    });

    it('getScenario embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: SCENARIO });

        const result = await getScenario('s1');

        expect(result).toEqual(SCENARIO);
        expect(mocks.get).toHaveBeenCalledWith('/projections/s1');
    });

    it('createScenario POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: SCENARIO });

        const result = await createScenario(CREATE);

        expect(result).toEqual(SCENARIO);
        expect(mocks.post).toHaveBeenCalledWith('/projections', CREATE);
    });

    it('updateScenario PUTs to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: SCENARIO });

        const result = await updateScenario('s1', CREATE);

        expect(result).toEqual(SCENARIO);
        expect(mocks.put).toHaveBeenCalledWith('/projections/s1', CREATE);
    });

    it('deleteScenario issues a DELETE on the id-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteScenario('s1');

        expect(mocks.del).toHaveBeenCalledWith('/projections/s1');
    });

    it('runProjection GETs the run sub-resource', async () => {
        mocks.get.mockResolvedValue({ data: RESULT });

        const result = await runProjection('s1');

        expect(result).toEqual(RESULT);
        expect(mocks.get).toHaveBeenCalledWith('/projections/s1/run');
    });

    it('compareScenarios POSTs the scenario id list', async () => {
        mocks.post.mockResolvedValue({ data: COMPARE });

        const result = await compareScenarios(['s1', 's2']);

        expect(result).toEqual(COMPARE);
        expect(mocks.post).toHaveBeenCalledWith('/projections/compare', {
            scenario_ids: ['s1', 's2'],
        });
    });

    it('optimizeSpending POSTs to the scenario optimize endpoint', async () => {
        mocks.post.mockResolvedValue({ data: GUARDRAIL });

        const result = await optimizeSpending('s1', OPTIMIZE);

        expect(result).toEqual(GUARDRAIL);
        expect(mocks.post).toHaveBeenCalledWith('/projections/s1/optimize', OPTIMIZE);
    });

    it('getGuardrailProfile returns the profile when present', async () => {
        mocks.get.mockResolvedValue({ data: GUARDRAIL });

        const result = await getGuardrailProfile('s1');

        expect(result).toEqual(GUARDRAIL);
        expect(mocks.get).toHaveBeenCalledWith('/projections/s1/guardrail');
    });

    it('getGuardrailProfile returns null when the request fails', async () => {
        mocks.get.mockRejectedValue(new Error('404'));

        const result = await getGuardrailProfile('s1');

        expect(result).toBeNull();
    });

    it('deleteGuardrailProfile issues a DELETE on the guardrail path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteGuardrailProfile('s1');

        expect(mocks.del).toHaveBeenCalledWith('/projections/s1/guardrail');
    });

    it('reoptimize POSTs to the reoptimize endpoint', async () => {
        mocks.post.mockResolvedValue({ data: GUARDRAIL });

        const result = await reoptimize('s1');

        expect(result).toEqual(GUARDRAIL);
        expect(mocks.post).toHaveBeenCalledWith('/projections/s1/guardrail/reoptimize');
    });

    it('propagates server errors from runProjection', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(runProjection('s1')).rejects.toThrow('500');
    });
});
