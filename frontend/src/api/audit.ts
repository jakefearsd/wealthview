import client from './client';
import type { AuditLogEntry } from '../types/audit';
import type { PageResponse } from '../types/common';

export async function getAuditLogs(
    page = 0,
    size = 50,
    entityType?: string
): Promise<PageResponse<AuditLogEntry>> {
    const params: Record<string, string | number> = { page, size };
    if (entityType) params.entity_type = entityType;
    const { data } = await client.get<PageResponse<AuditLogEntry>>('/audit-log', { params });
    return data;
}
