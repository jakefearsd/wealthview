# Projection Realism Phase 3 (User-Facing Surface) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the projection-realism engine reachable and legible from the UI — users set the portfolio they want to model (per-account allocation, cost basis, dividend yield), correct misclassified holdings, and see the realism in results (success probability, RMD, capital-gains tax).

**Architecture:** Six vertical slices, each a cut through the existing stack (`wealthview-core` DTOs/services → `wealthview-api` controllers → `frontend` components/types/api). Backend follows TDD + module rules + golden discipline; frontend uses Vitest + React Testing Library. No new modules; no dependency-direction changes. The one new endpoint (`PUT /securities/{symbol}/classification`) is additive. Slices 1 & 2 share the same account DTO/factory, so their backend work is one task and their frontend work is one task; all other slices are one backend and/or one frontend task.

**Tech Stack:** Java 25 / Spring Boot 4.1 / Maven multi-module / PostgreSQL 16 (Flyway, Testcontainers) backend; React 18+ / Vite / TypeScript / Vitest frontend.

## Global Constraints

- **DTOs are records** in `com.wealthview.core.projection.dto`; JPA entities never exposed; map via static factory on the record (`ProjectionAccountResponse.from(...)`). Constructor injection only; no `@Autowired` fields.
- **No wildcard imports.** Import order java.*, jakarta.*, org.*, com.*, static last. 4-space indent, 120-col, K&R.
- **Money is `BigDecimal`** (Java) / `numeric(19,4)` (SQL); never float/double for money in new persisted/DTO fields.
- **JSON is snake_case** globally (`asset_class`, `dividend_yield`, `cost_basis`, `rmd_amount`, `capital_gains_tax`, `success_probability`, `allocation`). Frontend types mirror these exact keys.
- **Backward compatibility:** every new input field is OPTIONAL with a default that reproduces today's behavior — allocation absent ⇒ derive-from-holdings; `dividend_yield` absent ⇒ 1.8% (`ScenarioParamsParser.DEFAULT_DIVIDEND_YIELD`); `cost_basis` absent ⇒ value=basis. Existing scenarios and goldens must NOT change except in Task 8.
- **Tenant isolation:** every query filters by the security-context `tenantId` (`principal.tenantId()`), NEVER a request parameter.
- **Validation:** allocation four percentages each 0–100, **sum == 100** when present; `dividend_yield` 0–10% (real, i.e. 0–0.10); `cost_basis` ≥ 0; reclassify `asset_class` ∈ {US_STOCK, INTL_STOCK, BOND, CASH}. Enforced in the service (business) + mirrored live in the form; the standard error envelope `ErrorResponse(error, message, status)` via the global `@RestControllerAdvice` carries failures.
- **Real-terms only:** all money is today's dollars; add a shared "all values in today's dollars" label to results (Task 7). No nominal toggle.
- **Coverage floors upheld:** core 90 / api 80 line; branch floors not lowered. Commit on `main`, one logical change per commit, conventional-commit messages; do NOT push.
- **AssetClass keys:** `US_STOCK→"us_stock"`, `INTL_STOCK→"intl_stock"`, `BOND→"bond"`, `CASH→"cash"` (via `AssetClass.key()` / `AssetClass.fromKey()`). `AssetAllocation(Map<AssetClass,BigDecimal>)` normalizes weights to sum 1.0; the entity stores `Map<String,BigDecimal>` keyed by those key strings and `ProjectionInputBuilder.parseAllocation` normalizes downstream — so storing raw percentages in the map is correct.

---

## File Structure

**Backend (`backend/wealthview-core/src/main/java/com/wealthview/core/projection/`)**
- `dto/CreateProjectionAccountRequest.java` — add `allocation` field (Task 1).
- `dto/ProjectionAccountResponse.java` — add `allocation` + `costBasis`; extend `from(...)` (Task 1).
- `dto/AllocationDto.java` — NEW record `(BigDecimal usStock, intlStock, bond, cash)` in percentages, shared request/response shape + mapping helpers (Task 1).
- `ScenarioCrudService.java` — `addAccountsToScenario` sets allocation; `mapAccounts` computes effective allocation + cost basis via injected `SecurityClassificationService` + `AccountService` (Task 1).
- `dto/ScenarioParamsSource.java` + `dto/ScenarioRequest.java` + `dto/ScenarioParams.java` — thread `dividendYield` (Task 3).
- `SecurityClassificationService.java` — add `setOverride(...)` (Task 5).

**Backend (`backend/wealthview-persistence/.../entity/`)**
- `SecurityClassOverrideEntity.java` — add `setAssetClass(...)` for upsert (Task 5).

**Backend (`backend/wealthview-api/src/main/java/com/wealthview/api/`)**
- `controller/SecurityClassificationController.java` — NEW `PUT /api/v1/securities/{symbol}/classification` (Task 5).
- `dto/ClassificationRequest.java` — NEW `(String assetClass)` (Task 5).
- `dto/SecurityClassificationResponse.java` — NEW `(String symbol, String assetClass)` (Task 5).

**Backend (`backend/wealthview-projection/.../`)**
- `MultiPoolYearDtoBuilder.java` + its `YearDtoInputs` + `DeterministicProjectionEngine.java` — thread `rmdAmount` + `ltcgTax` to the DTO (Task 8).
- `dto/ProjectionYearDto.java` (in core) — add `rmdAmount` + `capitalGainsTax` to `TaxBreakdown` + `Builder` (Task 8).
- `src/test/resources/golden/multi-pool-roth-conversion.json` — regen (Task 8).

**Frontend (`frontend/src/`)**
- `types/projection.ts` — extend `ScenarioAccountInput`, `ProjectionAccount`, `CreateScenarioRequest`, `ProjectionResult`, `ProjectionYear`, `GuardrailProfileResponse`, `GuardrailOptimizationRequest` (Tasks 2,4,6,7,9).
- `api/projections.ts` — `runProjection` return type; `api/securities.ts` NEW (Task 6).
- `components/AllocationEditor.tsx` — NEW (Task 2); `components/ScenarioForm.tsx` — account row + advanced (Tasks 2,4).
- `components/UnclassifiedSymbolsNotice.tsx` — NEW (Task 6); `pages/ProjectionDetailPage.tsx` (Tasks 6,7).
- `components/OptimizerResultsView.tsx` + `pages/SpendingOptimizerPage.tsx` (Task 7).
- `components/DataTableTab.tsx` + `components/IncomeTaxTab.tsx` + `components/TaxBreakdownChart.tsx` (Task 9).

---

## Task 1: Backend — per-account allocation + cost basis on the account DTOs (Slices 1+2)

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/AllocationDto.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/CreateProjectionAccountRequest.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionAccountResponse.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/ScenarioCrudService.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/ScenarioCrudServiceTest.java` (extend; create if absent), and any existing `AllocationDto`/mapping unit test location under the same package.

**Interfaces:**
- Consumes: `AssetClass.key()/fromKey()`, `AssetAllocation(Map<AssetClass,BigDecimal>)` + `AssetAllocation.ALL_US` + `weights()`; `SecurityClassificationService.deriveAllocation(UUID tenantId, UUID accountId) → AllocationResult(allocation, unclassifiedSymbols)`; `AccountService.computeCostBasis(AccountEntity, UUID tenantId)`; `ProjectionAccountEntity.setAllocation(Map<String,BigDecimal>)` / `getAllocation()` / `getCostBasis()`.
- Produces: `AllocationDto(BigDecimal usStock, BigDecimal intlStock, BigDecimal bond, BigDecimal cash)` with `toWeightMap()` (→ `Map<String,BigDecimal>` keyed by AssetClass.key(), percentages preserved) and `static AllocationDto fromAllocation(AssetAllocation)` (fractions×100). `CreateProjectionAccountRequest` gains `AllocationDto allocation()`. `ProjectionAccountResponse` gains `AllocationDto allocation()` + `BigDecimal costBasis()`; new factory `from(ProjectionAccountEntity, BigDecimal balance, AllocationDto allocation, BigDecimal costBasis)`.

- [ ] **Step 1: Write the failing test — AllocationDto round-trip + validation**

Create `AllocationDtoTest` in `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/AllocationDtoTest.java`:

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationDtoTest {

    @Test
    void toWeightMap_percentages_keyedByAssetClassKey() {
        var dto = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("5"));

        var map = dto.toWeightMap();

        assertThat(map).containsEntry("us_stock", new BigDecimal("60"))
                .containsEntry("intl_stock", new BigDecimal("20"))
                .containsEntry("bond", new BigDecimal("15"))
                .containsEntry("cash", new BigDecimal("5"));
    }

    @Test
    void validate_sumNot100_throws() {
        var dto = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("10"));

        assertThatThrownBy(dto::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void fromAllocation_normalizedFractions_toPercentages() {
        var alloc = AssetAllocation.fromDoubles(java.util.Map.of(
                AssetClass.US_STOCK, 0.6, AssetClass.BOND, 0.4));

        var dto = AllocationDto.fromAllocation(alloc);

        assertThat(dto.usStock()).isEqualByComparingTo("60");
        assertThat(dto.bond()).isEqualByComparingTo("40");
        assertThat(dto.intlStock()).isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 2: Run it — verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AllocationDtoTest`
Expected: FAIL — `AllocationDto` does not exist / compile error.

- [ ] **Step 3: Implement `AllocationDto`**

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-account asset allocation as four percentages (0–100) summing to 100. The wire/edit shape
 * for {@link CreateProjectionAccountRequest} and {@link ProjectionAccountResponse}; maps to the
 * entity's {@code Map<String,BigDecimal>} (keyed by {@link AssetClass#key()}) which
 * {@code AssetAllocation} normalizes downstream.
 */
public record AllocationDto(BigDecimal usStock, BigDecimal intlStock, BigDecimal bond, BigDecimal cash) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** Throws if any component is null/negative or the four do not sum to 100 (±0.01). */
    public void validate() {
        BigDecimal sum = BigDecimal.ZERO;
        for (var v : new BigDecimal[] {usStock, intlStock, bond, cash}) {
            if (v == null || v.signum() < 0) {
                throw new IllegalArgumentException("allocation percentages must be non-null and non-negative");
            }
            sum = sum.add(v);
        }
        if (sum.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0) {
            throw new IllegalArgumentException("allocation percentages must sum to 100 (got " + sum + ")");
        }
    }

    /** Percentages keyed by {@link AssetClass#key()}, dropping zero weights. */
    public Map<String, BigDecimal> toWeightMap() {
        var map = new LinkedHashMap<String, BigDecimal>();
        putIfPositive(map, AssetClass.US_STOCK, usStock);
        putIfPositive(map, AssetClass.INTL_STOCK, intlStock);
        putIfPositive(map, AssetClass.BOND, bond);
        putIfPositive(map, AssetClass.CASH, cash);
        return map;
    }

    private static void putIfPositive(Map<String, BigDecimal> map, AssetClass ac, BigDecimal v) {
        if (v != null && v.signum() > 0) {
            map.put(ac.key(), v);
        }
    }

    /** Normalized {@link AssetAllocation} (fractions summing to 1.0) → percentages. */
    public static AllocationDto fromAllocation(AssetAllocation allocation) {
        var w = allocation.weights();
        return new AllocationDto(pct(w, AssetClass.US_STOCK), pct(w, AssetClass.INTL_STOCK),
                pct(w, AssetClass.BOND), pct(w, AssetClass.CASH));
    }

    private static BigDecimal pct(Map<AssetClass, BigDecimal> w, AssetClass ac) {
        var frac = w.getOrDefault(ac, BigDecimal.ZERO);
        return frac.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
```

- [ ] **Step 4: Run it — verify pass**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AllocationDtoTest`
Expected: PASS.

- [ ] **Step 5: Add `allocation` to `CreateProjectionAccountRequest`**

Add the field (keep the existing back-compat constructor; add a new 6-arg-plus-allocation shape by making `allocation` the last field and giving a convenience constructor that defaults it to null):

```java
public record CreateProjectionAccountRequest(
        UUID linkedAccountId,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        BigDecimal costBasis,
        AllocationDto allocation,
        String accountType) {

    /** Back-compat for call sites predating cost_basis + allocation (defaults both to null). */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, null, null, accountType);
    }

    /** Back-compat for call sites that set cost_basis but predate allocation. */
    public CreateProjectionAccountRequest(UUID linkedAccountId, BigDecimal initialBalance,
                                          BigDecimal annualContribution, BigDecimal expectedReturn,
                                          BigDecimal costBasis, String accountType) {
        this(linkedAccountId, initialBalance, annualContribution, expectedReturn, costBasis, null, accountType);
    }
}
```

- [ ] **Step 6: Write the failing test — service wires allocation + response exposes effective allocation/costBasis**

Extend `ScenarioCrudServiceTest` (mirror its existing Mockito setup; mock `SecurityClassificationService` + `AccountService`). Add two tests:

```java
@Test
void createScenario_manualAccountWithAllocation_persistsWeightMap() {
    var alloc = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
            new BigDecimal("15"), new BigDecimal("5"));
    var request = scenarioRequestWithAccounts(List.of(new CreateProjectionAccountRequest(
            null, new BigDecimal("100000"), new BigDecimal("0"), null, new BigDecimal("90000"),
            alloc, "taxable")));

    service.createScenario(TENANT_ID, request);

    var saved = captureSavedScenario();
    assertThat(saved.getAccounts().get(0).getAllocation())
            .containsEntry("us_stock", new BigDecimal("60"))
            .containsEntry("cash", new BigDecimal("5"));
}

@Test
void createScenario_allocationSumNot100_throws() {
    var bad = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
            new BigDecimal("15"), new BigDecimal("10"));
    var request = scenarioRequestWithAccounts(List.of(new CreateProjectionAccountRequest(
            null, new BigDecimal("100000"), new BigDecimal("0"), null, null, bad, "taxable")));

    assertThatThrownBy(() -> service.createScenario(TENANT_ID, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("100");
}
```

(If `ScenarioCrudServiceTest` does not exist, create it with the standard Mockito wiring for the service's constructor dependencies. Add the `SecurityClassificationService` mock to the service constructor call.)

- [ ] **Step 7: Run it — verify it fails**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ScenarioCrudServiceTest`
Expected: FAIL — allocation not persisted / constructor arity.

- [ ] **Step 8: Wire allocation in `addAccountsToScenario` + validation**

In `ScenarioCrudService.addAccountsToScenario`, after the `setCostBasis` line, add:

```java
            if (acctReq.allocation() != null) {
                acctReq.allocation().validate();
                projAcct.setAllocation(acctReq.allocation().toWeightMap());
            }
```

- [ ] **Step 9: Extend `ProjectionAccountResponse` + `mapAccounts` to expose effective allocation + cost basis**

Replace the 2-arg factory with a 4-arg one carrying the effective allocation + cost basis (the only current caller is `mapAccounts`, updated below):

```java
public record ProjectionAccountResponse(
        UUID id,
        UUID linkedAccountId,
        String name,
        BigDecimal initialBalance,
        BigDecimal annualContribution,
        BigDecimal expectedReturn,
        BigDecimal costBasis,
        AllocationDto allocation,
        String accountType) {

    public static ProjectionAccountResponse from(ProjectionAccountEntity entity, BigDecimal balance,
                                                 AllocationDto allocation, BigDecimal costBasis) {
        var linked = entity.getLinkedAccount();
        return new ProjectionAccountResponse(
                entity.getId(),
                linked != null ? linked.getId() : null,
                linked != null ? linked.getName() : entity.getAccountType(),
                balance,
                entity.getAnnualContribution(),
                entity.getExpectedReturn(),
                costBasis,
                allocation,
                entity.getAccountType());
    }
}
```

Inject `SecurityClassificationService classificationService` into `ScenarioCrudService` (constructor). Rewrite `mapAccounts` to compute the effective allocation + cost basis in the same per-account loop that already computes `balance` (same query-cost pattern):

```java
    private List<ProjectionAccountResponse> mapAccounts(ProjectionScenarioEntity scenario, UUID tenantId) {
        return scenario.getAccounts().stream()
                .map(acct -> {
                    var linked = acct.getLinkedAccount();
                    var balance = linked != null
                            ? accountService.computeBalance(linked, tenantId)
                            : acct.getInitialBalance();
                    var allocation = effectiveAllocation(acct, tenantId);
                    var costBasis = linked != null
                            ? accountService.computeCostBasis(linked, tenantId)
                            : (acct.getCostBasis() != null ? acct.getCostBasis() : acct.getInitialBalance());
                    return ProjectionAccountResponse.from(acct, balance, allocation, costBasis);
                })
                .toList();
    }

    private AllocationDto effectiveAllocation(ProjectionAccountEntity acct, UUID tenantId) {
        if (acct.getAllocation() != null) {
            var weights = new java.util.EnumMap<AssetClass, BigDecimal>(AssetClass.class);
            acct.getAllocation().forEach((k, v) -> weights.put(AssetClass.fromKey(k), v));
            return AllocationDto.fromAllocation(new AssetAllocation(weights));
        }
        if (acct.getLinkedAccount() != null) {
            return AllocationDto.fromAllocation(
                    classificationService.deriveAllocation(tenantId, acct.getLinkedAccount().getId()).allocation());
        }
        return AllocationDto.fromAllocation(AssetAllocation.ALL_US);
    }
```

Update any other `ProjectionAccountResponse.from(entity, balance)` call site to the new 4-arg factory (search the module).

- [ ] **Step 10: Run it — verify pass + module build**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=AllocationDtoTest,ScenarioCrudServiceTest`
Expected: PASS. Then `cd backend && mvn -q -pl wealthview-core install -DskipTests` compiles clean.

- [ ] **Step 11: Commit**

```bash
git add backend/wealthview-core
git commit -m "feat(core): expose per-account allocation and cost basis on projection account DTOs

Adds AllocationDto (four percentages summing to 100) to
CreateProjectionAccountRequest and ProjectionAccountResponse, wires
ScenarioCrudService to persist a user allocation override and to return
the effective allocation (override, else holdings-derived, else ALL_US)
plus cost basis. Makes the allocation-driven return model reachable from
the API. Backward compatible: absent allocation still derives.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 2: Frontend — allocation editor + cost basis field in the account row (Slices 1+2)

**Files:**
- Create: `frontend/src/components/AllocationEditor.tsx`
- Create: `frontend/src/components/AllocationEditor.test.tsx`
- Modify: `frontend/src/types/projection.ts` (`ScenarioAccountInput`, `ProjectionAccount`, add `AllocationDto`)
- Modify: `frontend/src/components/ScenarioForm.tsx` (account row + `defaultAccount` + hydrate + serialize)
- Modify: `frontend/src/components/ScenarioForm.test.tsx`

**Interfaces:**
- Consumes: backend JSON `allocation: {us_stock,intl_stock,bond,cash}` (percentages), `cost_basis: number`.
- Produces: `AllocationInput` TS type; `<AllocationEditor value onChange />` emitting a valid-or-null allocation + a `valid` flag.

- [ ] **Step 1: Add types**

In `frontend/src/types/projection.ts` add:

```typescript
export interface AllocationInput {
    us_stock: number;
    intl_stock: number;
    bond: number;
    cash: number;
}
```

Extend `ScenarioAccountInput` (add `cost_basis?: number | null;` and `allocation?: AllocationInput | null;`) and `ProjectionAccount` (add `cost_basis: number | null;` and `allocation: AllocationInput | null;`).

- [ ] **Step 2: Write the failing test — AllocationEditor sum validation**

`frontend/src/components/AllocationEditor.test.tsx`:

```typescript
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AllocationEditor from './AllocationEditor';

describe('AllocationEditor', () => {
    it('shows the running total and flags a non-100 sum', () => {
        render(<AllocationEditor value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 10 }} onChange={vi.fn()} />);
        expect(screen.getByText(/105\s*%/)).toBeInTheDocument();
        expect(screen.getByText(/must sum to 100/i)).toBeInTheDocument();
    });

    it('emits the edited allocation on change', () => {
        const onChange = vi.fn();
        render(<AllocationEditor value={{ us_stock: 60, intl_stock: 20, bond: 15, cash: 5 }} onChange={onChange} />);
        fireEvent.change(screen.getByLabelText(/US Stocks/i), { target: { value: '70' } });
        expect(onChange).toHaveBeenCalledWith({ us_stock: 70, intl_stock: 20, bond: 15, cash: 5 });
    });
});
```

- [ ] **Step 3: Run it — verify fail**

Run: `cd frontend && npm run test -- AllocationEditor`
Expected: FAIL — module not found.

- [ ] **Step 4: Implement `AllocationEditor`**

Four labeled number inputs (US Stocks / Intl Stocks / Bonds / Cash), a running total row that turns red when `Math.abs(sum-100) > 0.01`, and a "reset to derived" button that calls `onReset` when provided. Each input's `onChange` emits the full updated `AllocationInput`. Use `<label htmlFor>` so `getByLabelText` works. Export a helper `isAllocationValid(a: AllocationInput | null): boolean` (true when null — "derive" — or sum≈100).

- [ ] **Step 5: Run it — verify pass**

Run: `cd frontend && npm run test -- AllocationEditor`
Expected: PASS.

- [ ] **Step 6: Wire into `ScenarioForm` account row**

- `defaultAccount()`: add `cost_basis: null, allocation: null`.
- Hydrate (line ~112): map `cost_basis: a.cost_basis ?? null, allocation: a.allocation ?? null`.
- In the account row JSX (after the Expected Return field, which is now relabeled "Override Return (%) — blank uses allocation"): render `<AllocationEditor value={acct.allocation ?? DERIVED_PLACEHOLDER} onChange={a => updateAccount(idx, 'allocation', a)} onReset={() => updateAccount(idx, 'allocation', null)} />`. When `acct.allocation` is null show the editor collapsed behind a "Customize allocation" toggle whose summary reads "Derived from holdings"; expanding seeds the four fields from a sensible default (100/0/0/0 for manual, or leave for the user).
- For cost basis: add a `<FormField label="Cost Basis">` with `<CurrencyInput>` rendered only when `!acct.linked_account_id` (manual); for linked accounts show the read-only derived value (available after a run/echo) — for the create form, hide it for linked.
- Serialize (line ~194): keep `expected_return: a.expected_return/100` but send `undefined` when blank so the override defers to allocation; include `cost_basis: a.cost_basis ?? null` and `allocation: a.allocation ?? null`. Block submit (disable Save) when any account's allocation is present but invalid (`!isAllocationValid`).

- [ ] **Step 7: Extend `ScenarioForm.test.tsx`**

Add a test that mounts the form, sets an account allocation to 70/20/15/5 via the editor, submits, and asserts `onSubmit.mock.calls[0][0].accounts[0].allocation` equals `{us_stock:70,intl_stock:20,bond:15,cash:5}`. Stub `AllocationEditor` if the real one is heavy, else use it directly.

- [ ] **Step 8: Run tests + typecheck**

Run: `cd frontend && npm run test -- ScenarioForm AllocationEditor && npm run typecheck`
Expected: PASS, no type errors.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): allocation editor and cost-basis field in the scenario account row

Adds a numeric US/Intl/Bond/Cash allocation editor (live total, sum=100
validation, reset-to-derived) and a cost-basis field for manual accounts.
The old expected-return field becomes an optional override that defers to
the allocation-derived return when blank.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 3: Backend — scenario-level dividend yield (Slice 3)

**Files:**
- Modify: `backend/wealthview-core/.../dto/ScenarioParamsSource.java` (add `dividendYield()`)
- Modify: `backend/wealthview-core/.../dto/ScenarioRequest.java` (add field)
- Modify: `backend/wealthview-core/.../dto/ScenarioParams.java` (`from` passes it through)
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/projection/dto/ScenarioParamsTest.java` (extend/create)

**Interfaces:**
- Produces: `ScenarioParamsSource.dividendYield() → BigDecimal`; `ScenarioRequest` gains `dividendYield` as its last field-before-lists position (place it after `primaryResidenceMortgageInterest`, before `accounts`).

- [ ] **Step 1: Write the failing test**

```java
@Test
void from_dividendYieldPresent_passesThrough() {
    var request = scenarioRequestWith(new BigDecimal("0.021")); // helper sets dividendYield
    var params = ScenarioParams.from(request);
    assertThat(params.dividendYield()).isEqualByComparingTo("0.021");
}

@Test
void from_dividendYieldNull_staysNullForDefault() {
    var request = scenarioRequestWith(null);
    assertThat(ScenarioParams.from(request).dividendYield()).isNull();
}
```

- [ ] **Step 2: Run it — verify fail**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ScenarioParamsTest`
Expected: FAIL — `dividendYield()` not on source / still hardcoded null.

- [ ] **Step 3: Implement**

- `ScenarioParamsSource`: add `BigDecimal dividendYield();`.
- `ScenarioRequest`: add `BigDecimal dividendYield,` after `primaryResidenceMortgageInterest`.
- `ScenarioParams.from`: replace the hardcoded trailing `null` with `source.dividendYield()` and delete the "not (yet) a user-configurable field" comment.
- Update every `ScenarioRequest(...)` constructor call site in tests/fixtures for the new arg (search the module + `wealthview-app` ITs).

- [ ] **Step 4: Run it — verify pass**

Run: `cd backend && mvn -q -pl wealthview-core test -Dtest=ScenarioParamsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core
git commit -m "feat(core): make scenario dividend yield a user-configurable request field

Threads dividend_yield through ScenarioParamsSource, ScenarioRequest, and
ScenarioParams.from (replacing the hardcoded null). Absent still defaults
to 1.8% via ScenarioParamsParser, so existing scenarios are unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013zqLcvSyE7cr1g5Z5m8zio"
```

---

## Task 4: Frontend — dividend yield advanced control (Slice 3)

**Files:**
- Modify: `frontend/src/types/projection.ts` (`CreateScenarioRequest` add `dividend_yield?: number | null`)
- Modify: `frontend/src/components/ScenarioForm.tsx` (advanced section control + state + serialize; percent→decimal at submit)
- Modify: `frontend/src/components/ScenarioForm.test.tsx`

**Interfaces:**
- Consumes: backend `dividend_yield` (decimal, e.g. 0.018).
- Produces: form sends `dividend_yield` as a decimal (input shown as percent, `/100` at submit); blank ⇒ omit the field.

- [ ] **Step 1: Add `dividend_yield?: number | null` to `CreateScenarioRequest`.**
- [ ] **Step 2: Write the failing test** — mount form, set Dividend Yield to `2.1`, submit, assert `onSubmit.mock.calls[0][0].dividend_yield` ≈ `0.021`.
- [ ] **Step 3: Run it — FAIL** (`cd frontend && npm run test -- ScenarioForm`).
- [ ] **Step 4: Implement** — add `dividendYield` to `ScenarioFormFields` state (default `1.8`), a `<FormField label="Dividend Yield (%)" helpText="Annual qualified-dividend drag on taxable accounts (default 1.8%)">` number input in the advanced/settings area, and at submit include `dividend_yield: dividendYield != null ? dividendYield/100 : undefined`. Hydrate from `params_json.dividend_yield * 100` on edit.
- [ ] **Step 5: Run it — PASS** + `npm run typecheck`.
- [ ] **Step 6: Commit** `feat(frontend): scenario dividend-yield advanced control`.

---

## Task 5: Backend — reclassify unclassified holdings endpoint (Slice 4)

**Files:**
- Modify: `backend/wealthview-persistence/.../entity/SecurityClassOverrideEntity.java` (add `setAssetClass`)
- Modify: `backend/wealthview-core/.../projection/SecurityClassificationService.java` (add `setOverride`)
- Create: `backend/wealthview-api/.../controller/SecurityClassificationController.java`
- Create: `backend/wealthview-api/.../dto/ClassificationRequest.java`, `.../dto/SecurityClassificationResponse.java`
- Test: `backend/wealthview-core/.../projection/SecurityClassificationServiceTest.java` (extend); `backend/wealthview-api/.../controller/SecurityClassificationControllerTest.java` (create); `backend/wealthview-persistence/.../repository/SecurityClassificationRepositoriesIntegrationTest.java` (extend with upsert IT).

**Interfaces:**
- Produces: `SecurityClassificationService.setOverride(UUID tenantId, String symbol, AssetClass assetClass) → AssetClass` (upsert; returns the stored class). Endpoint `PUT /api/v1/securities/{symbol}/classification`, body `{ "asset_class": "INTL_STOCK" }` → 200 `SecurityClassificationResponse(symbol, asset_class)`.

- [ ] **Step 1: Write the failing service test**

```java
@Test
void setOverride_newSymbol_persistsUpperCaseClass() {
    when(overrideRepository.findByTenantIdAndSymbol(TENANT, "SPAXX")).thenReturn(Optional.empty());

    var result = service.setOverride(TENANT, "SPAXX", AssetClass.CASH);

    assertThat(result).isEqualTo(AssetClass.CASH);
    verify(overrideRepository).save(argThat(e ->
            e.getSymbol().equals("SPAXX") && e.getAssetClass().equals("cash") && e.getTenantId().equals(TENANT)));
}

@Test
void setOverride_existingSymbol_updatesInPlace() {
    var existing = new SecurityClassOverrideEntity(TENANT, "SPAXX", "us_stock");
    when(overrideRepository.findByTenantIdAndSymbol(TENANT, "SPAXX")).thenReturn(Optional.of(existing));

    service.setOverride(TENANT, "SPAXX", AssetClass.CASH);

    assertThat(existing.getAssetClass()).isEqualTo("cash");
    verify(overrideRepository).save(existing);
}
```

- [ ] **Step 2: Run — FAIL** (`mvn -q -pl wealthview-core test -Dtest=SecurityClassificationServiceTest`).

- [ ] **Step 3: Implement** — add `setAssetClass(String)` setter to `SecurityClassOverrideEntity`; add to the service:

```java
    @Transactional
    public AssetClass setOverride(UUID tenantId, String symbol, AssetClass assetClass) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(assetClass, "assetClass must not be null");
        var existing = overrideRepository.findByTenantIdAndSymbol(tenantId, symbol);
        var entity = existing.orElseGet(() -> new SecurityClassOverrideEntity(tenantId, symbol, assetClass.key()));
        entity.setAssetClass(assetClass.key());
        overrideRepository.save(entity);
        return assetClass;
    }
```

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Write the failing controller test** — `@WebMvcTest(SecurityClassificationController.class)` + `@MockitoBean SecurityClassificationService`. Assert: PUT with `{"asset_class":"INTL_STOCK"}` → 200 body `{"symbol":"VXUS","asset_class":"intl_stock"}`; PUT with `{"asset_class":"BOGUS"}` → 400; verify service called with `principal.tenantId()`, `"VXUS"`, `AssetClass.INTL_STOCK`. (Mirror an existing `@WebMvcTest` controller test in the module for security/`@AuthenticationPrincipal` setup.)

- [ ] **Step 6: Run — FAIL** (controller absent).

- [ ] **Step 7: Implement the controller + DTOs**

`ClassificationRequest`:
```java
public record ClassificationRequest(@NotBlank String assetClass) {}
```
`SecurityClassificationResponse`:
```java
public record SecurityClassificationResponse(String symbol, String assetClass) {}
```
`SecurityClassificationController`:
```java
@RestController
@RequestMapping("/api/v1/securities")
public class SecurityClassificationController {

    private final SecurityClassificationService classificationService;

    public SecurityClassificationController(SecurityClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    @PutMapping("/{symbol}/classification")
    public ResponseEntity<SecurityClassificationResponse> setClassification(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable String symbol,
            @RequestBody @Valid ClassificationRequest request) {
        AssetClass assetClass;
        try {
            assetClass = AssetClass.fromKey(request.assetClass().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown asset_class: " + request.assetClass());
        }
        var saved = classificationService.setOverride(principal.tenantId(), symbol, assetClass);
        return ResponseEntity.ok(new SecurityClassificationResponse(symbol, saved.key()));
    }
}
```
(Confirm the global handler maps `IllegalArgumentException` → 400; if it maps `MethodArgumentNotValidException` for `@Valid` but not bare `IllegalArgumentException`, follow whatever the module's other controllers rely on — check `GlobalExceptionHandler`. Accept both `"INTL_STOCK"` and `"intl_stock"` by lowercasing.)

- [ ] **Step 8: Run — PASS.**

- [ ] **Step 9: Add the repository upsert IT** — extend `SecurityClassificationRepositoriesIntegrationTest` (Testcontainers): save an override, `findByTenantIdAndSymbol`, mutate via a second `setOverride`-style save, assert one row with the updated class and tenant scoping (a different tenant's same symbol is untouched).

- [ ] **Step 10: Run the module builds**

Run: `cd backend && mvn -q -pl wealthview-core,wealthview-api test -Dtest=SecurityClassificationServiceTest,SecurityClassificationControllerTest`
Expected: PASS. (The IT runs under `verify`.)

- [ ] **Step 11: Commit** `feat(api): add tenant security-classification override endpoint`.

---

## Task 6: Frontend — unclassified-symbols reclassify flow (Slice 4)

**Files:**
- Create: `frontend/src/api/securities.ts`, `frontend/src/components/UnclassifiedSymbolsNotice.tsx` (+ `.test.tsx`)
- Modify: `frontend/src/types/projection.ts` (`ProjectionResult` add `unclassified_symbols: string[] | null`)
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx` (render notice above summary cards; re-run after save)

**Interfaces:**
- Consumes: `runProjection` now returns `unclassified_symbols`; `PUT /securities/{symbol}/classification`.
- Produces: `setClassification(symbol, assetClass)`; `<UnclassifiedSymbolsNotice symbols onReclassified />`.

- [ ] **Step 1: Add `unclassified_symbols: string[] | null` to `ProjectionResult`.**
- [ ] **Step 2: `api/securities.ts`** — `export async function setClassification(symbol: string, assetClass: string) { const { data } = await client.put(\`/securities/${encodeURIComponent(symbol)}/classification\`, { asset_class: assetClass }); return data; }`.
- [ ] **Step 3: Write the failing test** — `UnclassifiedSymbolsNotice.test.tsx`: renders one row per symbol with an asset-class `<select>` (US Stock/Intl Stock/Bonds/Cash); choosing a class + clicking "Apply" calls `setClassification` (mocked) with the symbol + key and then `onReclassified` once all are applied.
- [ ] **Step 4: Run — FAIL.**
- [ ] **Step 5: Implement `UnclassifiedSymbolsNotice`** — an orange notice (mirror the spending-shortfall banner style in `ProjectionDetailPage.tsx:271-286`): "These holdings were modeled as US Stock because we couldn't classify them:", a per-symbol dropdown (options map label→`us_stock|intl_stock|bond|cash`), and an "Apply & re-run" button that `await`s all `setClassification` calls then calls `onReclassified`.
- [ ] **Step 6: Run — PASS.**
- [ ] **Step 7: Wire into `ProjectionDetailPage`** — inside the `{result && (...)}` block, above the summary-cards grid, render `{result.unclassified_symbols?.length ? <UnclassifiedSymbolsNotice symbols={result.unclassified_symbols} onReclassified={handleRun} /> : null}` (re-running clears the notice on success).
- [ ] **Step 8: Run tests + typecheck.** `cd frontend && npm run test -- UnclassifiedSymbolsNotice && npm run typecheck`.
- [ ] **Step 9: Commit** `feat(frontend): reclassify unclassified holdings from projection results`.

---

## Task 7: Frontend — success probability, confidence-copy fix, dynamic-sequencing knob, final net worth, real-terms label (Slice 5)

**Files:**
- Modify: `frontend/src/types/projection.ts` (`GuardrailProfileResponse` add `success_probability: number`; `GuardrailOptimizationRequest` add `dynamic_sequencing_bracket_rate?: number`; `ProjectionResult` add `final_net_worth: number | null`)
- Modify: `frontend/src/components/OptimizerResultsView.tsx` (success-probability card)
- Modify: `frontend/src/pages/SpendingOptimizerPage.tsx` (fix confidence help text; add dynamic-sequencing control + submit)
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx` (final-net-worth card + "today's dollars" label)
- Modify tests: `OptimizerResultsView.test.tsx` (create/extend), `SpendingOptimizerPage.test.tsx` (extend)

**Interfaces:** all fields already returned/accepted by the backend; this is a pure consumption + copy fix.

- [ ] **Step 1: Add the three type fields.**
- [ ] **Step 2: Write the failing test** — `OptimizerResultsView.test.tsx`: given a result with `success_probability: 0.91`, a "Success Probability" card renders "91%". And a `SpendingOptimizerPage` test asserting the conservative help text now contains "95%".
- [ ] **Step 3: Run — FAIL.**
- [ ] **Step 4: Implement**
  - `OptimizerResultsView`: widen the summary grid to 5 columns (`repeat(5, 1fr)`), add a "Success Probability" card `{pct(result.success_probability)}` before Failure Rate (green-tinted).
  - `SpendingOptimizerPage:429-433`: change the help text to `'95% confidence — ...'` / `'90% confidence — ...'` / `'80% confidence — ...'` to match `GuardrailProfileService` presets.
  - Add a "Dynamic-Sequencing Bracket Rate (%)" number input in Advanced Settings bound to new state `dynSeqBracketRate` (default null); at submit include `...(dynSeqBracketRate != null ? { dynamic_sequencing_bracket_rate: dynSeqBracketRate/100 } : {})`.
  - `ProjectionDetailPage`: add a "Net Worth" summary card `{result.final_net_worth != null ? formatCurrency(result.final_net_worth) : '—'}` and a small muted "All values in today's dollars." caption above the summary-cards grid.
- [ ] **Step 5: Run — PASS** + `npm run typecheck`.
- [ ] **Step 6: Commit** `feat(frontend): surface success probability, fix confidence copy, add net worth + dynamic-sequencing knob`.

---

## Task 8: Backend — per-year RMD amount + capital-gains tax on the year DTO (Slice 6)

**Files:**
- Modify: `backend/wealthview-core/.../projection/dto/ProjectionYearDto.java` (`TaxBreakdown` + `Builder`)
- Modify: `backend/wealthview-projection/.../MultiPoolYearDtoBuilder.java` (+ its `YearDtoInputs`)
- Modify: `backend/wealthview-projection/.../DeterministicProjectionEngine.java` (pass `rmdAmount` + `ltcgTax` into `YearDtoInputs`)
- Modify: `backend/wealthview-projection/src/test/resources/golden/multi-pool-roth-conversion.json` (regen)
- Test: `backend/wealthview-projection/.../MultiPoolYearDtoBuilderTest.java` (extend/create)

**Interfaces:**
- Consumes: `DeterministicProjectionEngine.processYear` local `rmdAmount` (BigDecimal, line ~297) and `ltcgTax` (BigDecimal, line ~319) — already computed, currently not threaded to the DTO.
- Produces: `ProjectionYearDto.TaxBreakdown` gains `rmdAmount` + `capitalGainsTax` (both `BigDecimal`, nullable, positive-or-null); JSON keys `rmd_amount`, `capital_gains_tax`.

- [ ] **Step 1: Write the failing test** — `MultiPoolYearDtoBuilderTest`: build a `YearDtoInputs` for a retired year with `rmdAmount = 12000` and `ltcgTax = 1500`; assert the resulting `ProjectionYearDto.tax().rmdAmount()` == 12000 and `.capitalGainsTax()` == 1500; and a non-retired year with zero ⇒ both null (positive-or-null).
- [ ] **Step 2: Run — FAIL** (`mvn -q -pl wealthview-projection test -Dtest=MultiPoolYearDtoBuilderTest`).
- [ ] **Step 3: Implement**
  - `TaxBreakdown`: add `BigDecimal rmdAmount, BigDecimal capitalGainsTax` (extend the record + `empty()` nulls).
  - `ProjectionYearDto.Builder`: add `rmdAmount(...)` + `capitalGainsTax(...)` setters and include them in the `TaxBreakdown` it builds.
  - `YearDtoInputs`: add `BigDecimal rmdAmount()` + `BigDecimal ltcgTax()` components.
  - `MultiPoolYearDtoBuilder.build`: `.rmdAmount(positiveOrNull(in.rmdAmount())).capitalGainsTax(positiveOrNull(in.ltcgTax()))`.
  - `DeterministicProjectionEngine`: pass the already-computed `rmdAmount` and `ltcgTax` locals into the `YearDtoInputs` construction (find where `YearDtoInputs` is assembled for the year and add the two args).
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Regenerate the golden**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=ProjectionGoldenFileTest -Dupdate.golden=true`
Then `git diff backend/wealthview-projection/src/test/resources/golden/multi-pool-roth-conversion.json`. **Verify by hand:** only `rmd_amount` / `capital_gains_tax` fields appear (on retirement years with a traditional RMD / taxable-withdrawal LTCG respectively); `tax_liability`, `federal_tax`, and every other value are byte-unchanged. The other two goldens (`simple-preretirement`, `tiered-spending-with-income`) must NOT change (they never reach retirement/RMD). If any other field moves, STOP and investigate.

- [ ] **Step 6: Re-run the golden test without the flag to confirm green**

Run: `cd backend && mvn -q -pl wealthview-projection test -Dtest=ProjectionGoldenFileTest`
Expected: PASS.

- [ ] **Step 7: Commit** `feat(projection): surface per-year RMD amount and capital-gains tax on the year DTO` (body notes the golden regen adds only the two new fields; capital_gains_tax is a display breakout already inside federal_tax, not additive to tax_liability).

---

## Task 9: Frontend — RMD + capital-gains columns/series in results (Slice 6)

**Files:**
- Modify: `frontend/src/types/projection.ts` (`ProjectionYear` add `rmd_amount: number | null; capital_gains_tax: number | null;`)
- Modify: `frontend/src/components/DataTableTab.tsx` (pool-details columns)
- Modify: `frontend/src/components/IncomeTaxTab.tsx` (columns, `hasCapGains` gating)
- Modify: `frontend/src/components/TaxBreakdownChart.tsx` (`capital_gains_tax` stacked bar)
- Modify tests: the corresponding `*.test.tsx` (extend or create a focused one)

**Interfaces:** consumes the two new `ProjectionYear` fields from Task 8.

- [ ] **Step 1: Add the two `ProjectionYear` fields.**
- [ ] **Step 2: Write the failing test** — a `DataTableTab`/`IncomeTaxTab` test: given yearly data with a year having `rmd_amount: 12000, capital_gains_tax: 1500`, the table renders "$12,000" in an RMD column and "$1,500" in a Cap-Gains Tax column, and columns are hidden when all years are null (mirror the `hasStateTax = yearlyData.some(...)` gating).
- [ ] **Step 3: Run — FAIL.**
- [ ] **Step 4: Implement**
  - `IncomeTaxTab`: add `const hasRmd = yearlyData.some(y => y.rmd_amount != null); const hasCapGains = yearlyData.some(y => y.capital_gains_tax != null);` add gated `<th>RMD</th>` / `<th>Cap-Gains Tax</th>` headers and matching cells (`formatCurrency`, `'-'` when null).
  - `DataTableTab`: in the "Show Pool Details" column group (alongside `tax_paid_from_*`), add gated RMD + Cap-Gains Tax columns.
  - `TaxBreakdownChart`: add `capital_gains_tax` to `ChartDataPoint`, map `y.capital_gains_tax ?? 0`, and add `<Bar dataKey="capital_gains_tax" stackId="tax" fill="#6a1b9a" name="Cap-Gains Tax" hide={!hasCapGains} />` following the SE-tax `hide` pattern. (Do NOT add an RMD bar to the tax stack — RMD is a withdrawal subset, not a tax bucket; RMD appears only in the tables.)
- [ ] **Step 5: Run — PASS** + `npm run typecheck`.
- [ ] **Step 6: Commit** `feat(frontend): show per-year RMD and capital-gains tax in results tables and chart`.

---

## Final verification (after all tasks)

- [ ] Backend full: `cd backend && mvn -q clean install -DskipITs -B` then `mvn -q verify -pl wealthview-app -B` (Testcontainers ITs). Both green.
- [ ] Frontend: `cd frontend && npm run test && npm run typecheck && npm run lint`. Green.
- [ ] Confirm only `multi-pool-roth-conversion.json` moved among goldens, adding only `rmd_amount`/`capital_gains_tax`.
- [ ] Nothing pushed.
