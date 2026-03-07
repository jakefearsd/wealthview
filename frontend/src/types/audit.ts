export interface AuditLogEntry {
    id: string;
    tenant_id: string;
    user_id: string | null;
    action: string;
    entity_type: string;
    entity_id: string | null;
    details: Record<string, unknown> | null;
    created_at: string;
}
