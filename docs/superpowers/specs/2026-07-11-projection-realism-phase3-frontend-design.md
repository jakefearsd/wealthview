# Projection Realism v2 — Phase 3 (User-Facing Surface) — Design

**Date:** 2026-07-11
**Status:** Approved (design), pending implementation plan
**Scope:** Third and final phase of the projection-realism-v2 effort. Phases 1–2 + the two Tier-2
tax items (RMDs, capital gains) landed the *engine* realism. Phase 3 makes that realism
**reachable and legible from the UI**: users create the portfolio they want to model (per-account
allocation, cost basis, dividend yield), correct misclassified holdings, and see the realism in
results (success probability, RMD, capital-gains tax). See
[[project-projection-realism-v2-phase1]] memory and the Phase 1/2/RMD/capital-gains specs for the
engine work this surfaces.

## Problem statement

The realism engine is largely **unreachable from the app**. The centerpiece — the allocation-driven
return model — is the worst case: `CreateProjectionAccountRequest` has no `allocation` field and
`ScenarioCrudService` never calls `setAllocation()`, so **every account created through the UI falls
back to 100% US stock** (`AssetAllocation.ALL_US` for hypothetical, holdings-derived for linked, but
never user-chosen). Cost basis is accepted for manual accounts but never returned (edit forms can't
show it). Dividend yield is modeled scenario-wide with a 1.8% default but hardcoded to `null` in
`ScenarioParams.from(ScenarioRequest)` — unreachable except by hand-editing `params_json`. Holdings
that default to US stock (e.g. SPAXX) are reported in `unclassified_symbols` but there is **no write
endpoint** to correct them. And the results UI ignores fields the backend already returns
(`success_probability`, `final_net_worth`) while the optimizer's risk-tolerance help text states the
wrong confidence numbers (85/70/60% vs. the engine's actual 95/90/80%).

## Decisions (from brainstorming)

| Fork | Decision |
|---|---|
| Phase scope | **One combined Phase 3** — inputs + outputs in a single spec/plan. |
| Build structure | **Vertical slices per capability** — each capability lands end-to-end (backend DTO/endpoint → core → frontend → tests/golden), independently shippable and reviewable. |
| Allocation editor control | **Numeric % inputs with a live running total** (four fields US/Intl/Bond/Cash, sum=100 validator, "reset to derived" link), inside the already-decided derive-from-holdings + editable-override container. |
| Allocation vs. expected-return | **Allocation is the primary control**; the existing per-account "expected return" becomes an optional **override** (blank ⇒ use the allocation-derived return). Single source of truth; matches the engine's `expectedReturnOverride ?? allocation-derived` precedence. |
| Unclassified holdings | **Reclassify in place** — warn, let the user assign the correct asset class, persist a tenant `security_class_override` via a new write endpoint, re-run. |
| Dollar frame | **Real-only, clearly labeled** — all money in today's dollars with a shared "today's dollars" label/tooltip; no nominal toggle. |
| Dividend yield placement | Scenario-level **Advanced** settings (engine models it per-scenario), default 1.8%. |
| Cost basis | Numeric field for **manual** accounts; read-only derived value shown for **linked** accounts. |

## Non-goals
- No new Maven modules and no change to the module dependency direction.
- No nominal-dollars display toggle (real-terms only, labeled).
- No per-symbol lot editing in the UI (initial lots aggregate per account, as the engine does).
- No reclassification of the global seed map — overrides are tenant-scoped only.
- No changes to the deterministic/MC engines' math; Phase 3 only *surfaces* computed values and
  *feeds* already-supported inputs. The one exception is populating two already-computed values
  (`rmd_amount`, `capital_gains_tax`) into the per-year response DTO.

---

## Architecture

Each slice is a vertical cut through the existing stack:

```
wealthview-api (request/response DTOs, controllers, @Valid)
   → wealthview-core (ScenarioCrudService, SecurityClassificationService, ScenarioParams, response mapping)
      → wealthview-persistence (SecurityClassOverrideRepository — reuse; entity columns already exist)
frontend (ScenarioForm + accounts sub-form, results/optimizer components, api/*, types/*)
```

Backend work follows the project's TDD + module rules + golden discipline; frontend uses Vitest +
React Testing Library. The one new endpoint is additive. No dependency-direction changes.

## The six slices

Each slice below is independently shippable. The plan may split a slice into a few TDD tasks
(e.g. backend request field, backend response field, frontend editor), but the slice is the unit of
user-visible value and the natural review boundary.

### Slice 1 — Per-account allocation (centerpiece)
- **Backend:** add `allocation` (an object `{us_stock, intl_stock, bond, cash}` of integer
  percentages) to `CreateProjectionAccountRequest` **and** `ProjectionAccountResponse`. Wire
  `ScenarioCrudService.addAccountsToScenario` to call `entity.setAllocation(...)` from the request
  (for both linked and manual accounts — an explicit allocation overrides the holdings-derived
  default). The response returns the effective allocation (user-set if present, else the
  holdings-/default-derived allocation so the edit form can show what the engine will use).
- **Validation:** each of the four percentages 0–100 integer; **sum must equal 100** when allocation
  is provided; absent/null allocation ⇒ derive-from-holdings (unchanged behavior).
- **Frontend:** numeric % editor in the accounts sub-form of `ScenarioForm` — four fields with a
  live running total, a sum=100 validator that blocks save, and a "reset to derived" link that
  clears the override. The old "expected return %" field moves to an optional "override return"
  advanced control (blank ⇒ allocation-derived).
- **Tests:** service unit (setAllocation from request, sum validation), controller (`@WebMvcTest`
  happy + 400 on bad sum), a Testcontainers round-trip IT (create with allocation → read back
  effective allocation), Vitest for the editor (sum validation, derive↔override).
- **Golden:** response-field addition is additive; existing golden *run* fixtures don't set
  allocation so their outputs are unchanged. No golden movement expected — verify.

### Slice 2 — Cost basis
- **Backend:** expose `cost_basis` on `ProjectionAccountResponse` (the request already accepts it via
  `CreateProjectionAccountRequest.costBasis()`). For linked accounts return the derived
  `Σ HoldingEntity.costBasis`; for manual accounts return the stored value.
- **Frontend:** numeric `cost_basis` field for **manual** accounts (type = taxable is where it
  matters); for **linked** accounts show the derived value read-only.
- **Tests:** service/response-mapping unit, controller assertion, Vitest field render.
- **Golden:** additive response field; no run-output change.

### Slice 3 — Dividend yield
- **Backend:** add `dividend_yield` to `ScenarioRequest`; wire `ScenarioParams.from(ScenarioRequest)`
  to pass it through (remove the hardcoded `null`). Absent/null ⇒ existing 1.8% default
  (`ScenarioParamsParser.DEFAULT_DIVIDEND_YIELD`) so existing scenarios are unchanged.
- **Validation:** 0–10% real; null allowed (defaults).
- **Frontend:** a % control in the scenario **Advanced** settings, default shown as 1.8%.
- **Tests:** `ScenarioParams.from` unit (value passes through; null ⇒ default), controller, Vitest.
- **Golden:** existing scenarios send no `dividend_yield` ⇒ default preserved ⇒ no golden movement.

### Slice 4 — Reclassify unclassified holdings
- **Backend:** add a write method `SecurityClassificationService.setOverride(tenantId, symbol,
  assetClass)` (upsert `security_class_override`) and a new controller endpoint
  **`PUT /api/v1/securities/{symbol}/classification`** with body `{ "asset_class": "INTL_STOCK" }`
  → 200 returning the resolved classification. `tenantId` from the security context. Validation:
  `asset_class` ∈ {US_STOCK, INTL_STOCK, BOND, CASH}; symbol non-blank. Idempotent upsert. The
  `security_class_override` table/migration (V068) already exists.
- **Frontend:** surface `unclassified_symbols` (already on `ProjectionRunResponse`) on the results
  page as a notice listing the defaulted symbols, each with an asset-class dropdown; on save call
  the new endpoint per symbol, then re-run the projection so the notice clears and allocation
  reflects the override.
- **Tests:** service unit (upsert + tenant scoping), controller (`@WebMvcTest` happy + 400 bad
  class + 404), a `@DataJpaTest` repository IT for the upsert query, Vitest for the reclassify flow.
- **Golden:** none (new endpoint + frontend-only surfacing).

### Slice 5 — Success probability + confidence-copy fix
- **Backend:** none — `GuardrailProfileResponse.successProbability` is already returned;
  `dynamic_sequencing_bracket_rate` already exists on `GuardrailOptimizationRequest`.
- **Frontend:** add `success_probability` to the `GuardrailProfileResponse` TS type and display it in
  `OptimizerResultsView` (alongside/instead of raw failure rate). **Fix the bug** in
  `SpendingOptimizerPage` risk-tolerance help text: 85/70/60% → **95/90/80%** to match
  `GuardrailProfileService` presets. Add `dynamic_sequencing_bracket_rate` to the FE
  `GuardrailOptimizationRequest` type + optimizer form control. Surface `final_net_worth` from
  `ProjectionRunResponse` in the deterministic results summary.
- **Tests:** Vitest for the success-probability render and the corrected copy; type additions.
- **Golden:** none (frontend-only).

### Slice 6 — Per-year RMD amount + capital-gains tax line
- **Backend:** add `rmd_amount` and `capital_gains_tax` to `ProjectionYearDto` (the per-year DTO),
  populated from values the engine already computes (RMD physical model; LTCG tax). Note the recent
  capital-gains followup folded the LTCG tax **into** `federal_tax` (so `federal_tax` = ordinary +
  realized-gains tax, and `tax_liability = federal_tax + state_tax`). `capital_gains_tax` is
  therefore a **display breakout of a sub-component already inside `federal_tax`** — surfaced so
  users can see how much of federal tax is realized-gains tax. It is NOT added to `tax_liability`;
  `tax_liability` and `federal_tax` are unchanged by this slice. Document the relationship
  (`capital_gains_tax ⊆ federal_tax`) so the numbers reconcile.
- **Frontend:** show `rmd_amount` and `capital_gains_tax` in `DataTableTab` / `IncomeTaxTab` /
  `TaxBreakdownChart` (a distinct series/column for realized-gains tax; an RMD callout/column).
- **Tests:** response-mapping unit (fields populated from engine values), Vitest for the new
  column/series, a golden regen with per-year values verified.
- **Golden:** **the only slice that moves goldens.** Additive per-year fields ⇒ affected fixtures
  gain `rmd_amount`/`capital_gains_tax`; RMD years show the amount, taxable-withdrawal years show the
  LTCG tax, `taxLiability` unchanged. Direction-verified regen, committed green within the slice.

## Cross-cutting rules

- **Validation layering:** Bean Validation annotations on request DTOs (`@Valid`), business checks in
  the service (allocation sum, tenant scoping), DB constraints as the final net. Frontend mirrors the
  sum=100 and range checks live for immediate feedback; server remains authoritative.
- **Backward compatibility:** every new input field is optional with a default that reproduces
  today's behavior (allocation absent ⇒ derived; dividend_yield absent ⇒ 1.8%; cost_basis absent ⇒
  value=basis). Existing scenarios and their goldens must not change except in Slice 6.
- **Error handling:** the standard error envelope (`{error, message, status}`) via the global
  `@RestControllerAdvice`; the frontend surfaces field errors inline (allocation sum, out-of-range).
- **Real-terms labeling:** a single shared "all values in today's dollars" label + tooltip applied to
  money charts/tables, satisfying the real-only decision in one place.
- **JSON naming:** snake_case throughout (`asset_class`, `dividend_yield`, `cost_basis`,
  `rmd_amount`, `capital_gains_tax`, `success_probability`), matching the global Jackson strategy.
- **Tenant isolation:** the reclassification endpoint and all account queries filter by the
  security-context `tenantId`, never a request parameter.

## Testing & coverage
- Backend: JUnit 5 + Mockito unit tests, `@WebMvcTest` controller tests (happy + 400/403/404),
  Testcontainers ITs for the reclassification upsert and the allocation round-trip. Coverage floors
  upheld: core 90 / api 80 line; branch floors not lowered.
- Frontend: Vitest + React Testing Library for the allocation editor, cost-basis field, dividend
  control, reclassify flow, success-probability render, and RMD/cap-gains columns. Simple
  presentational components may be skipped per the frontend testing policy.
- Full `mvn verify` (units + 5 gates + Testcontainers ITs, chunked) green before hand-off; frontend
  `npm run test` + `npm run typecheck` green. Nothing pushed without explicit instruction.

## Module / convention notes
- Records for all DTOs; JPA entities never exposed; static factory mapping
  (`ProjectionAccountResponse.from(...)`). Constructor injection only; no wildcard imports;
  `numeric(19,4)`/`BigDecimal` for money; `Optional` from finders. Commit on `main`, one logical
  change per commit (per slice/task), conventional-commit messages. This is the largest surface of
  the realism effort — the plan decomposes it into per-slice TDD tasks, each with its own tests and
  (where applicable) golden regen.
