import client from './client';
import type {
    Property,
    PropertyRequest,
    PropertyExpenseRequest,
    PropertyExpense,
    MonthlyCashFlowEntry,
    MonthlyCashFlowDetailEntry,
    PropertyValuation,
    PropertyAnalyticsResponse,
    ValuationRefreshResponse,
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

export async function listPropertyExpenses(propertyId: string): Promise<PropertyExpense[]> {
    const { data } = await client.get<PropertyExpense[]>(`/properties/${propertyId}/expenses`);
    return data;
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

export async function getCashFlowDetail(
    propertyId: string,
    from: string,
    to: string
): Promise<MonthlyCashFlowDetailEntry[]> {
    const { data } = await client.get<MonthlyCashFlowDetailEntry[]>(
        `/properties/${propertyId}/cashflow-detail`,
        { params: { from, to } }
    );
    return data;
}

export async function getValuationHistory(propertyId: string): Promise<PropertyValuation[]> {
    const { data } = await client.get<PropertyValuation[]>(
        `/properties/${propertyId}/valuations`
    );
    return data;
}

export async function refreshValuation(propertyId: string): Promise<ValuationRefreshResponse> {
    const { data } = await client.post<ValuationRefreshResponse>(
        `/properties/${propertyId}/valuations/refresh`
    );
    return data;
}

export async function selectZpid(propertyId: string, zpid: string): Promise<ValuationRefreshResponse> {
    const { data } = await client.post<ValuationRefreshResponse>(
        `/properties/${propertyId}/valuations/select-zpid`,
        { zpid }
    );
    return data;
}

export async function getPropertyAnalytics(
    propertyId: string,
    year?: number
): Promise<PropertyAnalyticsResponse> {
    const { data } = await client.get<PropertyAnalyticsResponse>(
        `/properties/${propertyId}/analytics`,
        { params: year ? { year } : {} }
    );
    return data;
}
