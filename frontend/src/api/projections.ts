import client from './client';
import type { Scenario, ProjectionResult, CreateScenarioRequest, UpdateScenarioRequest, CompareResponse } from '../types/projection';

export async function listScenarios(): Promise<Scenario[]> {
    const { data } = await client.get<Scenario[]>('/projections');
    return data;
}

export async function getScenario(id: string): Promise<Scenario> {
    const { data } = await client.get<Scenario>(`/projections/${id}`);
    return data;
}

export async function createScenario(request: CreateScenarioRequest): Promise<Scenario> {
    const { data } = await client.post<Scenario>('/projections', request);
    return data;
}

export async function updateScenario(id: string, request: UpdateScenarioRequest): Promise<Scenario> {
    const { data } = await client.put<Scenario>(`/projections/${id}`, request);
    return data;
}

export async function deleteScenario(id: string): Promise<void> {
    await client.delete(`/projections/${id}`);
}

export async function runProjection(id: string): Promise<ProjectionResult> {
    const { data } = await client.get<ProjectionResult>(`/projections/${id}/run`);
    return data;
}

export async function compareScenarios(ids: string[]): Promise<CompareResponse> {
    const { data } = await client.post<CompareResponse>('/projections/compare', { scenario_ids: ids });
    return data;
}
