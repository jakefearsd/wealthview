import client from './client';
import type {
    Property,
    PropertyRequest,
    PropertyIncomeRequest,
    PropertyExpenseRequest,
    MonthlyCashFlowEntry,
} from '../types/property';

export async function listProperties(): Promise<Property[]> {
    const { data } = await client.get<Property[]>('/properties');
    return data;
}

export async function getProperty(id: string): Promise<Property> {
    const { data } = await client.get<Property>(`/properties/${id}`);
    return data;
}

export async function createProperty(request: PropertyRequest): Promise<Property> {
    const { data } = await client.post<Property>('/properties', request);
    return data;
}

export async function updateProperty(id: string, request: PropertyRequest): Promise<Property> {
    const { data } = await client.put<Property>(`/properties/${id}`, request);
    return data;
}

export async function deleteProperty(id: string): Promise<void> {
    await client.delete(`/properties/${id}`);
}

export async function addPropertyIncome(
    propertyId: string,
    request: PropertyIncomeRequest
): Promise<void> {
    await client.post(`/properties/${propertyId}/income`, request);
}

export async function addPropertyExpense(
    propertyId: string,
    request: PropertyExpenseRequest
): Promise<void> {
    await client.post(`/properties/${propertyId}/expenses`, request);
}

export async function getCashFlow(
    propertyId: string,
    from: string,
    to: string
): Promise<MonthlyCashFlowEntry[]> {
    const { data } = await client.get<MonthlyCashFlowEntry[]>(
        `/properties/${propertyId}/cashflow`,
        { params: { from, to } }
    );
    return data;
}
