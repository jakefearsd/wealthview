[← Back to README](../README.md)

# API Reference

All endpoints are under `/api/v1/` and require JWT authentication (except `/api/v1/auth/**`).

## Authentication

| Endpoint                 | Method | Description              |
|--------------------------|--------|--------------------------|
| `/api/v1/auth/login`     | POST   | Login, returns JWT tokens |
| `/api/v1/auth/register`  | POST   | Register with invite code |
| `/api/v1/auth/refresh`   | POST   | Refresh access token      |

**Login request:**
```json
{ "email": "user@example.com", "password": "yourpassword" }
```

**Login response:**
```json
{ "access_token": "eyJ...", "refresh_token": "eyJ...", "email": "user@example.com", "role": "admin" }
```

Include the access token in subsequent requests:
```
Authorization: Bearer eyJ...
```

## Accounts & Holdings

| Endpoint                              | Method         | Description                   |
|---------------------------------------|----------------|-------------------------------|
| `/api/v1/accounts`                    | GET, POST      | List or create accounts       |
| `/api/v1/accounts/{id}`               | GET, PUT, DELETE | Account CRUD               |
| `/api/v1/accounts/{id}/transactions`  | GET, POST      | Transactions for account      |
| `/api/v1/accounts/{id}/holdings`      | GET            | Holdings for account          |
| `/api/v1/accounts/{id}/portfolio-history` | GET        | Theoretical portfolio history |
| `/api/v1/holdings`                    | POST           | Create manual holding         |
| `/api/v1/holdings/{id}`               | GET, PUT       | Get or update holding         |
| `/api/v1/transactions/{id}`           | PUT, DELETE    | Update or delete transaction  |

## Properties

| Endpoint                              | Method | Description              |
|---------------------------------------|--------|--------------------------|
| `/api/v1/properties`                  | GET, POST | List or create properties |
| `/api/v1/properties/{id}`             | GET, PUT, DELETE | Property CRUD     |
| `/api/v1/properties/{id}/income`      | GET, POST | List or add rental income |
| `/api/v1/properties/{id}/expenses`    | GET, POST | List or add property expense |
| `/api/v1/properties/income/{id}`      | DELETE | Delete income entry       |
| `/api/v1/properties/expenses/{id}`    | DELETE | Delete expense entry      |
| `/api/v1/properties/{id}/valuations`  | GET, POST | Valuation history       |
| `/api/v1/properties/valuations/refresh` | POST | Trigger Zillow valuation scrape for all properties |
| `/api/v1/properties/select-zpid`      | POST   | Select Zillow property ID for a property |
| `/api/v1/properties/{id}/analytics`   | GET    | Property analytics (cap rate, cash-on-cash return, equity growth, mortgage progress). Optional `year` query param. |

## Import & Prices

| Endpoint                       | Method | Description                     |
|--------------------------------|--------|---------------------------------|
| `/api/v1/import/csv`           | POST   | Upload CSV for import (multipart; `format` param: fidelity, vanguard, schwab) |
| `/api/v1/import/positions`     | POST   | Upload positions CSV for import (multipart; `format` param) |
| `/api/v1/import/ofx`           | POST   | Upload OFX/QFX for import (multipart) |
| `/api/v1/import/jobs`          | GET    | List import job history         |
| `/api/v1/prices`               | POST   | Add stock price                 |
| `/api/v1/prices/{symbol}/latest` | GET  | Latest price for symbol         |

## Projections

| Endpoint                        | Method | Description                                        |
|---------------------------------|--------|----------------------------------------------------|
| `/api/v1/projections`           | GET, POST | List or create scenarios                        |
| `/api/v1/projections/{id}`      | GET, PUT, DELETE | Get, update, or delete scenario            |
| `/api/v1/projections/compute/{id}` | POST | Run projection, get year-by-year results        |
| `/api/v1/projections/compare`   | POST   | Compare 2-3 scenarios side-by-side                 |

## Spending Profiles

| Endpoint                           | Method         | Description                    |
|------------------------------------|----------------|--------------------------------|
| `/api/v1/spending-profiles`        | GET, POST      | List or create profiles        |
| `/api/v1/spending-profiles/{id}`   | GET, PUT, DELETE | Profile CRUD                 |

## Income Sources

| Endpoint                           | Method         | Description                    |
|------------------------------------|----------------|--------------------------------|
| `/api/v1/income-sources`           | GET, POST      | List or create income sources  |
| `/api/v1/income-sources/{id}`      | GET, PUT, DELETE | Income source CRUD           |

Income source request/response includes `income_type`, `annual_amount`, `start_age`, `end_age`, `inflation_rate`, `one_time`, `tax_treatment`, and optional `property_id` for rental income linked to a specific property.

## Dashboard

| Endpoint                          | Method | Description                   |
|-----------------------------------|--------|-------------------------------|
| `/api/v1/dashboard/summary`       | GET    | Net worth, allocation breakdown, account balances |
| `/api/v1/dashboard/portfolio-history` | GET | Aggregate portfolio history (optional `years` query param) |

## Audit Log

| Endpoint                          | Method | Description                   |
|-----------------------------------|--------|-------------------------------|
| `/api/v1/audit-log`              | GET    | Paginated audit log. Query params: `page`, `size`, `entity_type` |

Returns paginated list of audit events with action, entity type, entity ID, timestamp, and JSONB details.

## Data Export

| Endpoint                          | Method | Description                   |
|-----------------------------------|--------|-------------------------------|
| `/api/v1/export/json`            | GET    | Full tenant data export as JSON |
| `/api/v1/export/csv/accounts`    | GET    | Export accounts as CSV        |
| `/api/v1/export/csv/transactions` | GET   | Export transactions as CSV    |
| `/api/v1/export/csv/holdings`    | GET    | Export holdings as CSV        |

## Notification Preferences

| Endpoint                                  | Method | Description                   |
|-------------------------------------------|--------|-------------------------------|
| `/api/v1/notifications/preferences`       | GET    | List user notification preferences |
| `/api/v1/notifications/preferences`       | PUT    | Update notification preferences |

## Tenant Management

| Endpoint                          | Method | Description                   |
|-----------------------------------|--------|-------------------------------|
| `/api/v1/tenant/invite-codes`     | GET, POST | Manage invite codes (admin) |
| `/api/v1/tenant/users`            | GET    | List tenant users (admin)     |
| `/api/v1/tenant/users/{id}/role`  | PUT    | Update user role (admin)      |
| `/api/v1/tenant/users/{id}`       | DELETE | Remove user (admin)           |
| `/api/v1/tenant/join`             | POST   | Join a tenant using invite code |

## Super-Admin

| Endpoint                          | Method | Description                   |
|-----------------------------------|--------|-------------------------------|
| `/api/v1/admin/tenants`           | GET, POST | List or create tenants     |
| `/api/v1/admin/tenants/details`   | GET    | List tenants with user counts |
| `/api/v1/admin/tenants/{id}/active` | PUT  | Enable or disable a tenant   |

---

## Related Docs

- [Administration Guide](administration.md) — Role matrix and admin capabilities
- [Data Model Reference](data_model.md) — Entity definitions and relationships
