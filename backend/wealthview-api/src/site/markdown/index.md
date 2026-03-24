# wealthview-api

The HTTP boundary of the application. Contains all REST controllers, Spring Security
configuration, JWT filter, and the global exception handler. No business logic lives here —
controllers validate, call one service method, and return a DTO.

---

## REST Controllers (17)

All controllers are in `com.wealthview.api.controller`, annotated `@RestController`,
and mapped under `/api/v1/`.

| Controller | Path Prefix | Resource |
|---|---|---|
| `AuthController` | `/auth` | Login, registration, token refresh |
| `AccountController` | `/accounts` | Financial account CRUD + holdings/transactions sub-resources |
| `HoldingController` | `/holdings` | Manual holding overrides |
| `TransactionController` | `/transactions` | Transaction updates and deletes |
| `PriceController` | `/prices` | Manual price entry; latest price lookup |
| `ImportController` | `/import` | CSV, OFX, positions file uploads; job history |
| `PropertyController` | `/properties` | Property CRUD + income, expenses, valuations |
| `ProjectionController` | `/projections` | Scenario CRUD, computation, comparison |
| `SpendingProfileController` | `/spending-profiles` | Tier-based spending profile CRUD |
| `GuardrailController` | `/guardrails` | Monte Carlo optimization; results retrieval |
| `IncomeSourceController` | `/income-sources` | Income source CRUD |
| `DashboardController` | `/dashboard` | Net worth summary; portfolio history |
| `TenantManagementController` | `/tenant` | Users, roles, invite codes (admin) |
| `SuperAdminController` | `/admin` | Tenant creation and listing (super-admin) |
| `AuditLogController` | `/audit-logs` | Audit log retrieval |
| `NotificationController` | `/notification-preferences` | Alert settings |
| `DataExportController` | `/export` | Full tenant data export |

---

## Spring Security Configuration

Security is configured in `com.wealthview.api.config`. The filter chain is:

```
JwtAuthenticationFilter
  ↓ validates bearer token
  ↓ extracts tenantId, userId, role → sets SecurityContext
  ↓
  CSRF disabled (stateless JWT API)
  Session management: STATELESS
  ↓
Role-based endpoint restrictions:
  /admin/**           → SUPER_ADMIN only
  /tenant/**          → ADMIN role
  DELETE endpoints    → ADMIN role
  POST/PUT endpoints  → MEMBER role
  GET endpoints       → VIEWER role (all authenticated users)
  /auth/**            → permitAll
```

### JWT Filter (`JwtAuthenticationFilter`)

Reads the `Authorization: Bearer <token>` header. Validates signature using the configured
secret key (HS256). Extracts claims: `tenant_id`, `user_id`, `role`. Builds an
`Authentication` and places it in the `SecurityContextHolder` for the duration of the request.

Expired tokens return `401`. Missing tokens on protected endpoints return `401`.
Insufficient role returns `403`.

---

## Global Exception Handler

`com.wealthview.api.exception.GlobalExceptionHandler` is annotated `@RestControllerAdvice`.

Maps domain exceptions to HTTP responses:

```java
@ExceptionHandler(EntityNotFoundException.class)
ResponseEntity<ErrorResponse> handle(EntityNotFoundException ex) {
    return ResponseEntity.status(404).body(
        new ErrorResponse("NOT_FOUND", ex.getMessage(), 404));
}
```

The `ErrorResponse` record: `{ error, message, status }`.

Jakarta Bean Validation failures (`MethodArgumentNotValidException`) are mapped to 400 with
a list of field-level errors.

---

## Request Validation

Every request body record carries Jakarta Bean Validation annotations:

```java
public record CreateAccountRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull AccountType type,
    @Size(max = 100) String institution
) {}
```

Controllers declare `@Valid` on the `@RequestBody` parameter. Validation failures short-circuit
before the service is called.

---

## Configuration Classes

| Class | Responsibility |
|---|---|
| `FinnhubConfig` | `@ConfigurationProperties` for Finnhub API key and rate-limit settings |
| `ZillowConfig` | Zillow scraper timeout and sync schedule |
| `SchedulingConfig` | `@EnableScheduling` + task executor thread pool |
| `PriceSyncScheduler` | `@Scheduled` daily price sync job |
| `SampleDataInitializer` | `ApplicationRunner` for `docker` profile; seeds demo tenant, user, accounts, transactions |
| `DevDataInitializer` | `ApplicationRunner` for `dev` profile; minimal dev seed |
| `SuperAdminInitializer` | `ApplicationRunner` on all profiles; ensures super-admin user exists |

---

## Testing Approach

Controller tests use `@WebMvcTest` with `@MockBean` for the service layer. This loads only the
web slice (filter chain, controller, exception handler) without the full application context.

```java
@WebMvcTest(AccountController.class)
class AccountControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean  AccountService accountService;
    @MockBean  JwtService jwtService;   // needed by security filter

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

Coverage target: **80%+** line coverage.
