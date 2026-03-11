[← Back to README](../README.md)

# Administration Guide

Guide for operating WealthView: tenant management, user roles, audit logging, data export, and scheduled jobs.

## Super-Admin

The super-admin account (`admin@wealthview.local`) has cross-tenant system management capabilities:

- **Create tenants** -- POST to `/api/v1/admin/tenants` with tenant name
- **List all tenants** -- GET `/api/v1/admin/tenants` or `/api/v1/admin/tenants/details` (includes user counts)
- **Enable/disable tenants** -- PUT `/api/v1/admin/tenants/{id}/active` with `{ "is_active": true/false }`. Disabled tenants cannot authenticate.

## Tenant Admin

Users with the `admin` role within a tenant can manage their tenant's users and access:

- **Generate invite codes** -- POST `/api/v1/tenant/invite-codes`. Codes expire after 7 days and are single-use.
- **List invite codes** -- GET `/api/v1/tenant/invite-codes` shows active and consumed codes.
- **Manage users** -- GET `/api/v1/tenant/users` lists all users; PUT `.../users/{id}/role` updates roles; DELETE `.../users/{id}` removes a user.

## Role Matrix

| Action | Super Admin | Tenant Admin | Member | Viewer |
|--------|:-----------:|:------------:|:------:|:------:|
| Create/manage tenants | Y | - | - | - |
| Enable/disable tenants | Y | - | - | - |
| Generate invite codes | Y | Y | - | - |
| Manage tenant users | Y | Y | - | - |
| Create/edit accounts | Y | Y | Y | - |
| Create/edit properties | Y | Y | Y | - |
| Create/edit projections | Y | Y | Y | - |
| Import transactions | Y | Y | Y | - |
| View dashboard/data | Y | Y | Y | Y |
| View audit log | Y | Y | - | - |
| Export data | Y | Y | Y | - |

## Audit Log

Every significant action in the system is recorded in an immutable audit log:

- **View audit history** -- GET `/api/v1/audit-log?page=0&size=20&entity_type=Account`
- Events include: entity creates, updates, deletes, and authentication events
- Each entry records: action, entity type, entity ID, JSONB details, IP address, and timestamp
- Audit records are never deleted and use raw UUIDs (not FKs) so they survive entity deletion
- Frontend: accessible via `/audit-log` page

## Data Export

Tenant data can be exported for backup or analysis:

- **Full JSON export** -- GET `/api/v1/export/json` returns all tenant data (accounts, transactions, holdings, properties, etc.) as a single JSON document
- **CSV exports** -- GET `/api/v1/export/csv/accounts`, `/csv/transactions`, `/csv/holdings` for per-entity CSV downloads
- Frontend: accessible via `/export` page

## Scheduled Jobs

| Job | Schedule | Description |
|-----|----------|-------------|
| **Price Sync** | Weekdays at 4:30 PM | Fetches latest closing prices from Finnhub for all tracked symbols. Disabled when `FINNHUB_API_KEY` is empty. |
| **Zillow Valuation Sync** | Sunday at 6:00 AM | Scrapes Zestimates for all properties with a `zillow_zpid`. Disabled when `ZILLOW_ENABLED` is false. |

---

## Related Docs

- [Backup Operations Guide](doing_backups.md) — Automated backup scheduling and restore procedures
- [Configuration Reference](configuration.md) — Environment variables and Spring profiles
- [Deployment Guide](deployment.md) — Production security checklist and resource requirements
- [API Reference](api_reference.md) — Full endpoint documentation
