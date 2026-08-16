# REST API Guide

**Base path:** `/api/v1/`

This page is the backend-developer view of the HTTP layer: the conventions every controller in
`wealthview-api` follows, and how to add one without re-deriving them. For the exhaustive
endpoint-by-endpoint reference, see `docs/reference/api-reference.md` in the repository root.

---

## Controller Inventory

28 controllers live in `com.wealthview.api.controller`, carrying 128 method-level request mappings
under their class-level `@RequestMapping` base paths.

| Controller | Base path |
|---|---|
| `AccountController` | `/api/v1/accounts` |
| `AdminPriceController` | `/api/v1/admin` |
| `AdminSystemController` | `/api/v1/admin` |
| `AdminTenantController` | `/api/v1/admin` |
| `AdminUserController` | `/api/v1/admin` |
| `AppVersionController` | `/api/v1/app` |
| `AuditLogController` | `/api/v1/audit-log` |
| `AuthController` | `/api/v1/auth` |
| `AuthMobileController` | `/api/v1/auth/token` |
| `DashboardController` | `/api/v1/dashboard` |
| `DataExportController` | `/api/v1/export` |
| `ExchangeRateController` | `/api/v1/exchange-rates` |
| `GuardrailController` | `/api/v1/projections/{scenarioId}` |
| `HoldingController` | `/api/v1` |
| `ImportController` | `/api/v1/import` |
| `IncomeSourceController` | `/api/v1/income-sources` |
| `MfaController` | `/api/v1/auth/mfa` |
| `MobileAppVersionAdminController` | `/api/v1/admin/mobile-versions` |
| `NotificationController` | `/api/v1/notifications/preferences` |
| `PriceController` | `/api/v1/prices` |
| `ProjectionController` | `/api/v1/projections` |
| `PropertyController` | `/api/v1/properties` |
| `SecurityClassificationController` | `/api/v1/securities` |
| `SessionController` | `/api/v1/auth/sessions` |
| `SpendingProfileController` | `/api/v1/spending-profiles` |
| `StockSplitController` | `/api/v1` |
| `TenantManagementController` | `/api/v1/tenant` |
| `TransactionController` | `/api/v1` |

`HoldingController`, `TransactionController` and `StockSplitController` map at `/api/v1` because
their paths are nested under a parent resource (`/accounts/{accountId}/transactions`) or split across
a read path and an admin write path (`/stock-splits` vs `/admin/stock-splits`).

---

## Authentication and Transport

There are **two transports over one auth pipeline**.

**Web (`AuthController`, `/api/v1/auth`)** — tokens are set as HttpOnly cookies (`access_token`,
`refresh_token`) and never enter a response body. That invariant is the XSS-exfiltration mitigation
for browser contexts. CSRF is enabled with a `CookieCsrfTokenRepository` (HttpOnly=false) and the
token is read from the `X-XSRF-TOKEN` header.

**Native mobile (`AuthMobileController`, `/api/v1/auth/token`)** — the same endpoints return tokens
**in the response body**. Native apps run no untrusted JavaScript, so the cookie invariant does not
apply; the client is expected to persist the refresh token in OS-managed secure storage. These
requests carry `Authorization: Bearer <jwt>` and skip CSRF.

`JwtAuthenticationFilter` accepts the Bearer header **first**, falling back to the `access_token`
cookie, and populates the security context identically for both. `GET /api/v1/auth/me` therefore
works for either transport.

Token lifetimes come from `app.jwt.*` in `application.yml`: access **1 hour** (3 600 000 ms), refresh
**24 hours** (86 400 000 ms).

MFA login is a two-step flow: `POST .../login` may return an MFA-required response instead of
tokens, which the client answers with `POST .../mfa/challenge`. MFA enrolment and recovery codes are
managed separately under `/api/v1/auth/mfa`.

---

## Tenant Isolation

The JWT carries `user_id`, `tenant_id`, `role` and `session_id`. `JwtAuthenticationFilter`
deserialises them into a `TenantUserPrincipal`, which controllers receive via
`@AuthenticationPrincipal`:

```java
@GetMapping("/{id}")
public ResponseEntity<AccountResponse> get(
        @AuthenticationPrincipal TenantUserPrincipal principal,
        @PathVariable UUID id) {
    return ResponseEntity.ok(accountService.get(principal.tenantId(), id));
}
```

**`tenantId` always comes from the principal, never from a request parameter, path variable or body
field.** Every service method that queries data takes it as its first argument and passes it into a
repository finder (`findByTenant_IdAndId(...)`). A controller that accepts a tenant id from the wire
is a bug, not a convenience.

A second, ORM-level backstop sits underneath: a Hibernate `tenantFilter` is enabled on the session at
every `@Transactional` boundary, so a query that forgot its tenant predicate still cannot return
another tenant's rows. It is disabled for unauthenticated and `SUPER_ADMIN` contexts — see
`data-model.md` — so it does not remove the obligation to filter explicitly.

---

## Authorization

Authorization is expressed **entirely in the `SecurityConfig` filter chain** — there is no
`@PreAuthorize`, `@Secured` or `@RolesAllowed` anywhere in the API module. Roles are granted as
`ROLE_<ROLE>` from `TenantUserPrincipal.getAuthorities()`.

The matcher order that matters (first match wins):

| Pattern | Requirement |
|---|---|
| `/actuator/health` | permit all |
| `/actuator/**` | `SUPER_ADMIN` |
| `POST /api/v1/auth/logout`, `/api/v1/auth/token/logout` | authenticated |
| `/api/v1/auth/sessions`, `/api/v1/auth/sessions/**` | authenticated |
| `/api/v1/auth/mfa/**` (enumerated) | authenticated |
| `/api/v1/auth/**` (everything else) | permit all |
| `GET /api/v1/app/version-check` | permit all |
| `/api/v1/admin/prices/**` | `ADMIN` or `SUPER_ADMIN` |
| `/api/v1/admin/**` | `SUPER_ADMIN` |
| `POST`/`PUT`/`DELETE` on `/api/v1/prices/**` | `ADMIN` or `SUPER_ADMIN` |
| `/api/v1/tenant/invite-codes*`, `/api/v1/tenant/users*` writes | `ADMIN` or `SUPER_ADMIN` |
| `GET /api/v1/**` | authenticated |
| `POST`/`PUT`/`DELETE` `/api/v1/**` | `ADMIN`, `MEMBER` or `SUPER_ADMIN` |

The practical rule: **any authenticated role can read; writes need MEMBER or better; anything under
`/api/v1/admin/**` is SUPER_ADMIN** (with the price-admin carve-out). A `VIEWER` is read-only by
virtue of the last three rows.

Under the `loadtest` profile only, `/actuator/prometheus` and `/actuator/metrics` are additionally
permitted anonymously. Every other profile leaves the posture unchanged.

---

## JSON Conventions

Jackson is configured globally with `property-naming-strategy: SNAKE_CASE`
(`application.yml`), so Java records written in camelCase serialise to `snake_case` on the wire —
matching the PostgreSQL column names. Do **not** hand-annotate `@JsonProperty` to achieve this.

Request and response bodies are **records**. Entities are never returned from a controller; map with
a static factory on the record (`AccountResponse.from(entity)`) rather than MapStruct or ModelMapper.

`ScenarioParams` additionally carries an explicit
`@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` because it is serialised standalone
into the `params_json` jsonb column, outside the HTTP `ObjectMapper`.

---

## Request Validation

Controllers validate with `@Valid` plus Jakarta Bean Validation annotations on the request record.
Business validation (ownership, state transitions, cross-field rules) belongs in the service, not
the controller.

> **Trap:** `@NotNull List<X>` validates the *list*, not its elements. A nested collection needs
> `@Valid List<@Valid X>` or element constraints are silently skipped and the invalid element blows
> up later as a 500 instead of a 400.

---

## Error Envelope

`GlobalExceptionHandler` (`@RestControllerAdvice`) is the only place exceptions are translated.
Controllers do not catch. Every error serialises as `ErrorResponse`:

```json
{
  "error": "NOT_FOUND",
  "message": "Account abc123 not found for tenant xyz",
  "status": 404
}
```

| HTTP | `error` | Triggering exception |
|---|---|---|
| 400 | `BAD_REQUEST` | `MethodArgumentNotValidException` (bean validation), `IllegalArgumentException`, `InvalidInviteCodeException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`, `DateTimeParseException`, `UncheckedIOException`, `DataIntegrityViolationException` |
| 401 | `UNAUTHORIZED` | `BadCredentialsException`, `InvalidSessionException` |
| 403 | `FORBIDDEN` | `AccessDeniedException`, `TenantAccessDeniedException` |
| 404 | `NOT_FOUND` | `EntityNotFoundException`, `NoResourceFoundException` |
| 409 | `CONFLICT` | `DuplicateEntityException`, `IllegalStateException` |
| 413 | `PAYLOAD_TOO_LARGE` | `MaxUploadSizeExceededException` |
| 429 | `RATE_LIMITED` | `RateLimitFilter` (written directly, see below) |
| 503 | `SERVICE_UNAVAILABLE` | `ServiceUnavailableException` |
| 500 | `INTERNAL_SERVER_ERROR` | anything else — the message is a fixed generic string, never the exception text |

Two responses bypass the advice because they are raised **inside the filter chain**, where
`@RestControllerAdvice` cannot see them: the 401 authentication entry point and the 403 access-denied
handler serialise the same envelope by hand in `SecurityConfig`. Keep them in sync if the envelope
shape ever changes.

---

## Pagination

Paged endpoints accept `?page=0&size=25` and return `PageResponse<T>` from
`com.wealthview.core.common`:

```json
{
  "data": [ ... ],
  "page": 0,
  "size": 25,
  "total": 142
}
```

**The collection field is `data`, not Spring's `content`.** Build it with
`PageResponse.from(page, Mapper::from)` — never return a raw Spring `Page`, whose JSON shape is
unstable across Boot versions and leaks pageable internals.

---

## Rate Limiting

`RateLimitFilter` applies to `/api/**` only, on a fixed 60-second window:

* **`/api/v1/auth/**` — 60 requests per minute, keyed by client IP.**
* **Everything else — 300 requests per minute, keyed by authenticated principal.**

Every response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`. Over the
limit returns 429 with the standard envelope. Windows are swept every 60s, or earlier once 50 000
keys are tracked. The filter is disabled with `app.rate-limit.enabled=false`.

---

## Adding an Endpoint

Follow the TDD vertical slice in `development-guide.md`. The API-layer checklist:

1. Controller method takes `@AuthenticationPrincipal TenantUserPrincipal principal` and passes
   `principal.tenantId()` into the service. No business logic in the controller.
2. Request/response types are records in `com.wealthview.api.dto` (or a core DTO where the service
   already owns the shape). No entity leaks.
3. Field names stay camelCase in Java; the global SNAKE_CASE strategy handles the wire.
4. `@Valid` on the request body; nested collections get `@Valid` on the element type too.
5. Throw a domain exception from the service — do not build error responses in the controller.
6. Confirm the new path lands on the intended `SecurityConfig` matcher. A new `/api/v1/**` write is
   MEMBER+ by default; anything narrower needs an explicit rule *above* the catch-alls.
7. Test with `@WebMvcTest(FooController.class)` + `MockMvc`, mocking the service with
   `@MockitoBean` (Boot 4 — `@MockBean` is gone). Cover the happy path plus 400/401/403/404.
