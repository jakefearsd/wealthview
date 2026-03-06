import client from './client';
import type { Scenario, ProjectionResult, CreateScenarioRequest } from '../types/projection';

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

export async function deleteScenario(id: string): Promise<void> {
    await client.delete(`/projections/${id}`);
}

export async function runProjection(id: string): Promise<ProjectionResult> {
    const { data } = await client.get<ProjectionResult>(`/projections/${id}/run`);
    return data;
}
