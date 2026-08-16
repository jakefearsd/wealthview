# wealthview-api

The HTTP boundary of the application. Contains all REST controllers, Spring Security
configuration, the JWT filter, servlet filters, and the global exception handler. No business
logic lives here — controllers validate, call one service method, and return a DTO.

Depends only on `wealthview-core`. It never sees `wealthview-persistence`,
`wealthview-import`, or `wealthview-projection`.

---

## REST Controllers (28)

All controllers are in `com.wealthview.api.controller`, annotated `@RestController`,
and mapped under `/api/v1/`. The path column is each class's `@RequestMapping` prefix.

### Portfolio

| Controller | Path Prefix | Resource |
|---|---|---|
| `AccountController` | `/api/v1/accounts` | Account CRUD + theoretical history |
| `HoldingController` | `/api/v1` | Holdings per account; manual holding overrides |
| `TransactionController` | `/api/v1` | Transactions per account; update and delete |
| `PriceController` | `/api/v1/prices` | Manual price entry; latest price lookup |
| `StockSplitController` | `/api/v1` | Split listing; admin manual entry, un-apply, sync trigger |
| `SecurityClassificationController` | `/api/v1/securities` | Per-symbol asset-class override |
| `ExchangeRateController` | `/api/v1/exchange-rates` | Tenant-scoped currency rates |
| `ImportController` | `/api/v1/import` | CSV, positions, and OFX uploads; job history |
| `DashboardController` | `/api/v1/dashboard` | Net worth summary, portfolio history, snapshot projection |

### Properties & Projections

| Controller | Path Prefix | Resource |
|---|---|---|
| `PropertyController` | `/api/v1/properties` | Property CRUD, income/expenses, valuations, cash flow, analytics, depreciation, ROI |
| `ProjectionController` | `/api/v1/projections` | Scenario CRUD, run, compare |
| `GuardrailController` | `/api/v1/projections/{scenarioId}` | Monte Carlo optimize, fetch, delete, reoptimize |
| `SpendingProfileController` | `/api/v1/spending-profiles` | Tier-based spending profile CRUD |
| `IncomeSourceController` | `/api/v1/income-sources` | Income source CRUD |

### Auth, Tenancy & Admin

| Controller | Path Prefix | Resource |
|---|---|---|
| `AuthController` | `/api/v1/auth` | Cookie-transport login, MFA challenge, register, refresh, logout, `/me` |
| `AuthMobileController` | `/api/v1/auth/token` | Bearer-transport equivalents for the mobile app |
| `MfaController` | `/api/v1/auth/mfa` | TOTP setup, verify, disable, recovery-code regeneration, status |
| `SessionController` | `/api/v1/auth/sessions` | List and revoke per-device sessions |
| `TenantManagementController` | `/api/v1/tenant` | Invite codes, users, roles (tenant admin) |
| `AdminTenantController` | `/api/v1/admin` | Tenant creation, listing, activation (super-admin) |
| `AdminUserController` | `/api/v1/admin` | User listing, password reset, activation |
| `AdminSystemController` | `/api/v1/admin` | System stats, login activity, runtime config |
| `AdminPriceController` | `/api/v1/admin` | Price sync, Yahoo fetch/save, CSV upload, history admin |
| `MobileAppVersionAdminController` | `/api/v1/admin/mobile-versions` | Per-platform minimum version policy |

### Other

| Controller | Path Prefix | Resource |
|---|---|---|
| `AuditLogController` | `/api/v1/audit-log` | Audit log retrieval |
| `NotificationController` | `/api/v1/notifications/preferences` | Alert settings |
| `DataExportController` | `/api/v1/export` | Full tenant export (JSON) and per-domain CSV |
| `AppVersionController` | `/api/v1/app` | Anonymous mobile force-update version check |

Paged endpoints return the `PageResponse` record from `wealthview-core`, whose payload field is
`data` (not `content`). JSON field names are `snake_case` globally
(`spring.jackson.property-naming-strategy: SNAKE_CASE`).

---

## Spring Security Configuration

Security is configured in `com.wealthview.api.security.SecurityConfig`. Route matchers use
**`PathPatternRequestMatcher`** — `AntPathRequestMatcher` is not used anywhere in this module.

```
JwtAuthenticationFilter (added before UsernamePasswordAuthenticationFilter)
  ↓ reads Authorization: Bearer <token>, falling back to the auth cookie
  ↓ extracts userId, tenantId, email, role, sessionId
  ↓ builds a TenantUserPrincipal → SecurityContext (and populates MDC)
  ↓
  Session management: STATELESS
  CSRF: enabled with a double-submit cookie (X-XSRF-TOKEN header),
        bypassed for login/register/refresh/mfa-challenge, the whole
        /api/v1/auth/token/** mobile surface, and any request that
        already carries an Authorization: Bearer header
  ↓
Route rules (evaluated in order):
  /actuator/health                    → permitAll
  /actuator/**                        → SUPER_ADMIN
  /api/v1/auth/me, logout, sessions,
    and all /auth/mfa/* endpoints     → authenticated
  /api/v1/auth/**                     → permitAll
  GET /api/v1/app/version-check       → permitAll
  /api/v1/admin/prices/**             → ADMIN or SUPER_ADMIN
  /api/v1/admin/**                    → SUPER_ADMIN
  price + tenant admin mutations      → ADMIN or SUPER_ADMIN
  GET    /api/v1/**                   → authenticated
  POST/PUT/DELETE /api/v1/**          → ADMIN, MEMBER, or SUPER_ADMIN
  GET /**                             → permitAll (SPA static assets)
```

Response headers are hardened in the same class: `X-Content-Type-Options`, `frameOptions: deny`,
HSTS (1 year, includeSubDomains, preload), a `default-src 'self'` Content-Security-Policy, and
a `Permissions-Policy` that disables geolocation, microphone, camera, and payment.

CORS is configured for `/api/**` from `app.cors.allowed-origins`, with credentials allowed and
`Set-Cookie` exposed. `PasswordEncoder` is BCrypt at strength 12.

### JWT Filter (`JwtAuthenticationFilter`)

Reads the `Authorization: Bearer <token>` header and falls back to the auth cookie when absent,
so the web SPA and the mobile app share one filter. Validates the signature, extracts the
claims, and places a `TenantUserPrincipal` in the `SecurityContextHolder` for the duration of
the request. It also seeds the SLF4J MDC (`tenantId`, request id from `X-Request-ID`) for
structured logging.

Expired or missing tokens on protected endpoints return `401`; insufficient role returns `403`.
Both bodies are written by `SecurityConfig`'s own entry-point / access-denied handlers, because
failures inside the filter chain never reach `@RestControllerAdvice`.

---

## Servlet Filters

| Filter | Responsibility |
|---|---|
| `RateLimitFilter` | Fixed 60 s windows: 300 requests per authenticated user for `/api/**`, 60 per IP for auth endpoints. Tracks at most 50 000 keys, sweeps expired windows every 60 s, and exports a `wealthview.ratelimit.tracked_keys` gauge. Disabled with `app.rate-limit.enabled=false`. |
| `RequestLoggingFilter` | Per-request structured log line; pairs with `LogSanitizer` so untrusted values can never forge log records. |

---

## Global Exception Handler

`com.wealthview.api.exception.GlobalExceptionHandler` is annotated `@RestControllerAdvice`
and returns the `ErrorResponse` record `{ error, message, status }`.

| Exception | Status | `error` code |
|---|---|---|
| `EntityNotFoundException`, `NoResourceFoundException` | 404 | `NOT_FOUND` |
| `InvalidSessionException`, `BadCredentialsException` | 401 | `UNAUTHORIZED` |
| `AccessDeniedException`, `TenantAccessDeniedException` | 403 | `FORBIDDEN` |
| `DuplicateEntityException`, `IllegalStateException` | 409 | `CONFLICT` |
| `InvalidInviteCodeException`, `IllegalArgumentException`, `MethodArgumentNotValidException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`, `DateTimeParseException`, `UncheckedIOException`, `DataIntegrityViolationException` | 400 | `BAD_REQUEST` |
| `MaxUploadSizeExceededException` | 413 | `PAYLOAD_TOO_LARGE` |
| `ServiceUnavailableException` | 503 | `SERVICE_UNAVAILABLE` |
| anything else | 500 | `INTERNAL_ERROR` |

Jakarta Bean Validation failures are summarised into the `message` field; the raw exception is
never leaked to the client. Every handler records an error metric before responding.

---

## Request Validation

Every request body record carries Jakarta Bean Validation annotations, and controllers declare
`@Valid` on the `@RequestBody` parameter, so validation failures short-circuit before the
service is called.

Note that `@Valid` must appear on the *element type* of a collection parameter — a bare
`@NotNull List<X>` validates the list but never its elements.

---

## Other Classes in This Module

| Package | Contents |
|---|---|
| `api.config` | `SpaWebConfig` + `SpaPathResourceResolver` — forwards unknown non-API paths to `index.html` so client-side routing works on refresh |
| `api.common` | `ClientIpResolver`, `PageRequests` (shared pagination parsing) |
| `api.dto` | The handful of response records that are genuinely API-shaped rather than domain-shaped: `ErrorResponse`, `ProjectionRunResponse`, `DepreciationScheduleResponse`, `SecurityClassificationResponse`, `ClassificationRequest`, `ConfigValueRequest` |
| `api.logging` | `LogSanitizer` |

Integration/config beans such as `FinnhubConfig`, `ZillowConfig`, `SchedulingConfig`,
and the data initializers live in **`wealthview-app`**, not here.

---

## Testing Approach

Controller tests use `@WebMvcTest` with **`@MockitoBean`** for the service layer — `@MockBean`
was removed in Spring Boot 4 and appears nowhere in the codebase. The slice loads the filter
chain, controller, and exception handler without the full application context.

```java
@WebMvcTest(AccountController.class)
class AccountControllerTest {
    @Autowired      MockMvc mockMvc;
    @MockitoBean    AccountService accountService;
    @MockitoBean    JwtTokenProvider jwtTokenProvider;   // needed by the security filter

    @Test
    void getAccount_whenNotFound_returns404() throws Exception {
        when(accountService.findById(any(), any()))
            .thenThrow(new EntityNotFoundException("Account not found"));
        mockMvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID())
                    .header("Authorization", "Bearer test-token"))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
```

Full request-to-database coverage lives in `wealthview-app`'s Failsafe `*IT` classes.

Coverage gates: **80%** line, **0.85** branch (enforced by `jacoco:check` on `mvn verify`).
