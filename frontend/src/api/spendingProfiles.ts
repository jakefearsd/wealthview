import client from './client';
import type { SpendingProfile, CreateSpendingProfileRequest, UpdateSpendingProfileRequest } from '../types/projection';

export async function listSpendingProfiles(): Promise<SpendingProfile[]> {
    const { data } = await client.get<SpendingProfile[]>('/spending-profiles');
    return data;
}

export async function getSpendingProfile(id: string): Promise<SpendingProfile> {
    const { data } = await client.get<SpendingProfile>(`/spending-profiles/${id}`);
    return data;
}

export async function createSpendingProfile(request: CreateSpendingProfileRequest): Promise<SpendingProfile> {
    const { data } = await client.post<SpendingProfile>('/spending-profiles', request);
    return data;
}

export async function updateSpendingProfile(id: string, request: UpdateSpendingProfileRequest): Promise<SpendingProfile> {
    const { data } = await client.put<SpendingProfile>(`/spending-profiles/${id}`, request);
    return data;
}

export async function deleteSpendingProfile(id: string): Promise<void> {
    await client.delete(`/spending-profiles/${id}`);
}
