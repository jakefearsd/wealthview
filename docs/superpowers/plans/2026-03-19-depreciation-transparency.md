# Depreciation Transparency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface depreciation inputs on the property form, add a depreciation schedule to property analytics, add per-property rental breakdown to projection output, and add a depreciation tax shield summary.

**Architecture:** Four vertical slices — (1) property form depreciation section (frontend-only, fields already exist in API), (2) depreciation schedule endpoint + analytics tab, (3) per-property rental breakdown in projection engine + frontend, (4) tax shield summary (frontend aggregation). Each slice is independently testable.

**Tech Stack:** Java 21 / Spring Boot, React 18 / TypeScript / Vite, existing `DepreciationCalculator`, `RentalLossCalculator`, `IncomeSourceProcessor`.

**Spec:** `docs/superpowers/specs/2026-03-19-depreciation-transparency-design.md`

---

## File Map

### Backend — New Files
| File | Purpose |
|------|---------|
| `backend/wealthview-api/src/main/java/com/wealthview/api/dto/DepreciationScheduleResponse.java` | Response DTO for depreciation schedule endpoint |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/RentalPropertyYearDetail.java` | Per-property per-year rental breakdown record |

### Backend — Modified Files
| File | Change |
|------|--------|
| `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java` | Add `getDepreciationSchedule()` method; add validation for `in_service_date` required when depreciation enabled |
| `backend/wealthview-api/src/main/java/com/wealthview/api/controller/PropertyController.java` | Add `GET /{id}/depreciation-schedule` endpoint |
| `backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java` | Collect per-property `RentalPropertyYearDetail` list from rental processing |
| `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionYearDto.java` | Add `rentalPropertyDetails` field |
| `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java` | Pass `rentalPropertyDetails` through to `ProjectionYearDto` in all constructor calls |
| `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java` | Add `rentalPropertyDetails` (null) to `ProjectionYearDto` constructors in `SinglePool.buildYearDto()` (line 174) and `MultiPool.buildYearDto()` (line 402) |

### Backend — Test Files
| File | Change |
|------|--------|
| `backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyServiceTest.java` | Tests for `getDepreciationSchedule()` and `in_service_date` validation |
| `backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java` | Tests for per-property detail collection |

### Frontend — Modified Files
| File | Change |
|------|--------|
| `frontend/src/components/PropertyForm.tsx` | Add Depreciation collapsible section with 4 fields + computed display |
| `frontend/src/components/PropertyAnalyticsSection.tsx` | Add Depreciation Schedule tab |
| `frontend/src/api/properties.ts` | Add `getDepreciationSchedule()` API function |
| `frontend/src/types/property.ts` | Add `DepreciationScheduleResponse` and `DepreciationScheduleYear` interfaces |
| `frontend/src/types/projection.ts` | Add `RentalPropertyYearDetail` interface, update `ProjectionYear` |
| `frontend/src/pages/ProjectionDetailPage.tsx` | Expandable per-property rows in Income Tax tab, Tax Shield summary section |

---

## Task 1: Property Form — Depreciation Section

**Files:**
- Modify: `frontend/src/components/PropertyForm.tsx` (after line ~131, following the Financial Assumptions pattern)

This is a frontend-only task. The backend already accepts these fields — the form just doesn't send them.

- [ ] **Step 1: Read the current PropertyForm.tsx to understand the collapsible section pattern**

The existing Financial Assumptions section (lines 100-131) uses:
- A boolean state prop `showFinancialAssumptions` toggled by a button
- Conditional render: `{props.showFinancialAssumptions && (<div>...</div>)}`
- Grid layout: `display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem'`
- Container style: `padding: '1rem', background: '#f5f5f5', borderRadius: 8`

- [ ] **Step 2: Add depreciation state props to the component**

The parent component that uses `PropertyForm` needs two new state values: `showDepreciation: boolean` and its setter. Check the parent page (`PropertyDetailPage.tsx` or wherever `PropertyForm` is rendered) and add the state there, then pass it as props.

Also ensure the form state includes the depreciation fields from the existing `PropertyRequest` interface: `in_service_date`, `land_value`, `depreciation_method`, `useful_life_years`. These are already on the `PropertyRequest` type — they just need to be wired into the form state and sent on submit.

- [ ] **Step 3: Add the Depreciation collapsible section to PropertyForm**

After the Financial Assumptions section (line ~131), add a new collapsible section following the same pattern:

```tsx
{/* Depreciation Section */}
<div style={{ marginTop: '1rem' }}>
  <button
    type="button"
    onClick={() => props.onShowDepreciationChange(!props.showDepreciation)}
    style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontWeight: 500, fontSize: '0.95rem' }}
  >
    {props.showDepreciation ? '▼ Hide Depreciation' : '▶ Show Depreciation'}
  </button>
</div>
{props.showDepreciation && (
  <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8, marginTop: '0.5rem' }}>
    <h4 style={{ margin: '0 0 1rem 0' }}>Depreciation</h4>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
      {/* Depreciation Method dropdown */}
      <label>
        Depreciation Method
        <select
          value={form.depreciation_method || 'none'}
          onChange={e => setForm({ ...form, depreciation_method: e.target.value })}
          style={{ width: '100%', padding: '0.5rem' }}
        >
          <option value="none">None</option>
          <option value="straight_line">Straight-Line</option>
        </select>
      </label>

      {/* In-Service Date */}
      {form.depreciation_method !== 'none' && (
        <label>
          In-Service Date
          <input
            type="date"
            value={form.in_service_date || ''}
            onChange={e => setForm({ ...form, in_service_date: e.target.value })}
            style={{ width: '100%', padding: '0.5rem' }}
          />
        </label>
      )}

      {/* Land Value */}
      {form.depreciation_method !== 'none' && (
        <label>
          Land Value ($)
          <input
            type="number"
            value={form.land_value ?? ''}
            onChange={e => setForm({ ...form, land_value: e.target.value ? Number(e.target.value) : undefined })}
            style={{ width: '100%', padding: '0.5rem' }}
          />
        </label>
      )}

      {/* Useful Life */}
      {form.depreciation_method !== 'none' && (
        <label>
          Useful Life (years)
          <input
            type="number"
            step="0.5"
            value={form.useful_life_years ?? 27.5}
            onChange={e => setForm({ ...form, useful_life_years: e.target.value ? Number(e.target.value) : undefined })}
            style={{ width: '100%', padding: '0.5rem' }}
          />
        </label>
      )}
    </div>

    {/* Computed read-only display */}
    {form.depreciation_method !== 'none' && form.land_value != null && form.purchase_price != null && (
      <div style={{ marginTop: '1rem', padding: '0.75rem', background: '#e8f5e9', borderRadius: 6, fontSize: '0.9rem' }}>
        <strong>Computed Depreciation Summary</strong>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem', marginTop: '0.5rem' }}>
          <div>Depreciable Basis: <strong>${((form.purchase_price || 0) - (form.land_value || 0)).toLocaleString()}</strong></div>
          <div>Annual Depreciation: <strong>${(((form.purchase_price || 0) - (form.land_value || 0)) / (form.useful_life_years || 27.5)).toLocaleString(undefined, { maximumFractionDigits: 0 })}</strong></div>
        </div>
        {/* Note: "First Year (prorated)" and "Depreciation End Year" from the spec require
            the mid-month convention calculation. These are deferred to the Depreciation Schedule
            tab (Task 4) where the full schedule is fetched from the backend. Duplicating the
            mid-month logic in JS here would be error-prone. */}
        {(form.land_value || 0) >= (form.purchase_price || 0) && (
          <div style={{ color: '#d32f2f', marginTop: '0.5rem' }}>
            Land value must be less than purchase price for depreciation.
          </div>
        )}
      </div>
    )}
  </div>
)}
```

Adapt variable names and patterns to match the existing code style in `PropertyForm.tsx` (the exact form state variable name, onChange pattern, and styling may differ — match what's there).

- [ ] **Step 4: Ensure depreciation fields are included in form submission**

Verify the form submit handler includes `in_service_date`, `land_value`, `depreciation_method`, `useful_life_years` in the request payload. If the form doesn't explicitly list fields, check if it spreads the form object — the fields may already be included. If not, add them.

When `depreciation_method` is not "none" and `in_service_date` is blank, auto-fill with `purchase_date` before submission.

- [ ] **Step 5: Verify in the browser**

Run: `docker compose up --build -d`
Navigate to a property, open the Depreciation section, enter values, save, and verify they persist on reload.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/PropertyForm.tsx
# Also add the parent page if modified for state props
git commit -m "feat(frontend): add depreciation section to property form

Surfaces existing backend fields (depreciation_method, in_service_date,
land_value, useful_life_years) in a collapsible Depreciation section on
the property form. Shows computed depreciable basis and annual amount.
Fields hidden when method is None."
```

---

## Task 2: Backend — Depreciation Schedule Endpoint

**Files:**
- Create: `backend/wealthview-api/src/main/java/com/wealthview/api/dto/DepreciationScheduleResponse.java`
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java`
- Modify: `backend/wealthview-api/src/main/java/com/wealthview/api/controller/PropertyController.java`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyServiceTest.java`

- [ ] **Step 1: Write the failing service test for getDepreciationSchedule**

Add to `PropertyServiceTest.java`:

```java
@Test
void getDepreciationSchedule_straightLine_returnsScheduleWithCumulativeAndRemaining() {
    var tenantId = UUID.randomUUID();
    var propertyId = UUID.randomUUID();
    var property = mock(PropertyEntity.class);
    when(property.getId()).thenReturn(propertyId);
    when(property.getDepreciationMethod()).thenReturn("straight_line");
    when(property.getPurchasePrice()).thenReturn(new BigDecimal("400000"));
    when(property.getLandValue()).thenReturn(new BigDecimal("100000"));
    when(property.getInServiceDate()).thenReturn(LocalDate.of(2024, 1, 1));
    when(property.getUsefulLifeYears()).thenReturn(new BigDecimal("27.5"));
    when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
            .thenReturn(Optional.of(property));

    var result = propertyService.getDepreciationSchedule(tenantId, propertyId);

    assertThat(result).isNotNull();
    assertThat(result.depreciationMethod()).isEqualTo("straight_line");
    assertThat(result.depreciableBasis()).isEqualByComparingTo("300000");
    assertThat(result.schedule()).isNotEmpty();
    // First entry should be 2024
    var firstEntry = result.schedule().get(0);
    assertThat(firstEntry.taxYear()).isEqualTo(2024);
    assertThat(firstEntry.cumulativeTaken()).isEqualByComparingTo(firstEntry.annualDepreciation());
    // Last entry's remaining basis should be 0
    var lastEntry = result.schedule().get(result.schedule().size() - 1);
    assertThat(lastEntry.remainingBasis()).isEqualByComparingTo("0.0000");
}

@Test
void getDepreciationSchedule_methodNone_throwsIllegalArgument() {
    var tenantId = UUID.randomUUID();
    var propertyId = UUID.randomUUID();
    var property = mock(PropertyEntity.class);
    when(property.getDepreciationMethod()).thenReturn("none");
    when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
            .thenReturn(Optional.of(property));

    assertThatThrownBy(() -> propertyService.getDepreciationSchedule(tenantId, propertyId))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void getDepreciationSchedule_propertyNotFound_throwsEntityNotFound() {
    var tenantId = UUID.randomUUID();
    var propertyId = UUID.randomUUID();
    when(propertyRepository.findByTenant_IdAndId(tenantId, propertyId))
            .thenReturn(Optional.empty());

    assertThatThrownBy(() -> propertyService.getDepreciationSchedule(tenantId, propertyId))
            .isInstanceOf(EntityNotFoundException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=PropertyServiceTest`
Expected: Compilation error — `getDepreciationSchedule` method doesn't exist yet.

- [ ] **Step 3: Create the DepreciationScheduleResult record in core**

The service needs to return a result that the controller can map to a response. Add a record in `PropertyService.java` (or a new file in the `dto` package):

File: `backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/DepreciationScheduleResult.java`

```java
package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DepreciationScheduleResult(
        String depreciationMethod,
        BigDecimal depreciableBasis,
        BigDecimal usefulLifeYears,
        LocalDate inServiceDate,
        List<YearEntry> schedule
) {
    public record YearEntry(
            int taxYear,
            BigDecimal annualDepreciation,
            BigDecimal cumulativeTaken,
            BigDecimal remainingBasis
    ) {}
}
```

- [ ] **Step 4: Implement getDepreciationSchedule in PropertyService**

Add to `PropertyService.java`. The service needs `DepreciationCalculator` injected — add it to the constructor.

```java
@Transactional(readOnly = true)
public DepreciationScheduleResult getDepreciationSchedule(UUID tenantId, UUID propertyId) {
    var property = propertyRepository.findByTenant_IdAndId(tenantId, propertyId)
            .orElseThrow(() -> new EntityNotFoundException("Property not found: " + propertyId));

    if ("none".equals(property.getDepreciationMethod())) {
        throw new IllegalArgumentException("Depreciation is not configured for this property");
    }

    var landValue = property.getLandValue() != null ? property.getLandValue() : BigDecimal.ZERO;
    var depreciableBasis = property.getPurchasePrice().subtract(landValue);
    var schedule = depreciationCalculator.computeStraightLine(
            property.getPurchasePrice(), landValue,
            property.getInServiceDate(), property.getUsefulLifeYears());

    var cumulative = BigDecimal.ZERO;
    var entries = new java.util.ArrayList<DepreciationScheduleResult.YearEntry>();
    for (var entry : schedule.entrySet()) {
        cumulative = cumulative.add(entry.getValue());
        entries.add(new DepreciationScheduleResult.YearEntry(
                entry.getKey(),
                entry.getValue(),
                cumulative,
                depreciableBasis.subtract(cumulative)));
    }

    return new DepreciationScheduleResult(
            property.getDepreciationMethod(),
            depreciableBasis,
            property.getUsefulLifeYears(),
            property.getInServiceDate(),
            entries);
}
```

Inject `DepreciationCalculator` into `PropertyService`:
- Add field: `private final DepreciationCalculator depreciationCalculator;`
- Add to constructor parameter list and assign

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=PropertyServiceTest`
Expected: All three new tests pass.

- [ ] **Step 6: Create DepreciationScheduleResponse in api module**

File: `backend/wealthview-api/src/main/java/com/wealthview/api/dto/DepreciationScheduleResponse.java`

```java
package com.wealthview.api.dto;

import com.wealthview.core.property.dto.DepreciationScheduleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DepreciationScheduleResponse(
        String depreciationMethod,
        BigDecimal depreciableBasis,
        BigDecimal usefulLifeYears,
        LocalDate inServiceDate,
        List<YearEntry> schedule
) {
    public record YearEntry(
            int taxYear,
            BigDecimal annualDepreciation,
            BigDecimal cumulativeTaken,
            BigDecimal remainingBasis
    ) {}

    public static DepreciationScheduleResponse from(DepreciationScheduleResult result) {
        var entries = result.schedule().stream()
                .map(e -> new YearEntry(e.taxYear(), e.annualDepreciation(), e.cumulativeTaken(), e.remainingBasis()))
                .toList();
        return new DepreciationScheduleResponse(
                result.depreciationMethod(), result.depreciableBasis(),
                result.usefulLifeYears(), result.inServiceDate(), entries);
    }
}
```

- [ ] **Step 7: Add endpoint to PropertyController**

Add to `PropertyController.java` before the closing brace (after line ~180):

```java
@GetMapping("/{id}/depreciation-schedule")
public ResponseEntity<DepreciationScheduleResponse> getDepreciationSchedule(
        @AuthenticationPrincipal TenantUserPrincipal principal,
        @PathVariable UUID id) {
    var result = propertyService.getDepreciationSchedule(principal.tenantId(), id);
    return ResponseEntity.ok(DepreciationScheduleResponse.from(result));
}
```

Add import for `DepreciationScheduleResponse`.

- [ ] **Step 8: Run full backend build**

Run: `cd backend && mvn clean install -DskipTests && mvn test -pl wealthview-core -Dtest=PropertyServiceTest`
Expected: Compiles and tests pass.

- [ ] **Step 9: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/property/dto/DepreciationScheduleResult.java \
       backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java \
       backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyServiceTest.java \
       backend/wealthview-api/src/main/java/com/wealthview/api/dto/DepreciationScheduleResponse.java \
       backend/wealthview-api/src/main/java/com/wealthview/api/controller/PropertyController.java
git commit -m "feat(api,core): add depreciation schedule endpoint

GET /api/v1/properties/{id}/depreciation-schedule returns a year-by-year
straight-line depreciation schedule with cumulative taken and remaining
basis. Service delegates to existing DepreciationCalculator. Returns 400
if depreciation method is none, 404 if property not found."
```

---

## Task 3: Backend — Validate in_service_date Required When Depreciation Enabled

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java:380-395`
- Test: `backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PropertyServiceTest.java`:

```java
@Test
void applyDepreciationFields_straightLineWithoutInServiceDate_throwsIllegalArgument() {
    // Create a request with straight_line but no in_service_date
    // This should throw because in_service_date is required when method != none
    // Use a PropertyRequest with depreciationMethod = "straight_line" and inServiceDate = null
    // Verify via create() or update() which calls applyDepreciationFields internally
}
```

The exact test structure depends on how `PropertyServiceTest` currently tests create/update. The key assertion: calling `create` or `update` with `depreciation_method = "straight_line"` and `in_service_date = null` should throw `IllegalArgumentException` with a message about `in_service_date` being required.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=PropertyServiceTest#applyDepreciationFields_straightLineWithoutInServiceDate_throwsIllegalArgument`
Expected: FAIL — currently no validation, property saves with null in_service_date.

- [ ] **Step 3: Add validation to applyDepreciationFields**

In `PropertyService.java`, modify `applyDepreciationFields()` (lines 380-395):

```java
private void applyDepreciationFields(PropertyEntity property, PropertyRequest request) {
    var method = request.depreciationMethod();
    if (method == null) {
        method = "none";
    }
    if (!VALID_DEPRECIATION_METHODS.contains(method)) {
        throw new IllegalArgumentException(
                "Invalid depreciation method: " + method + ". Must be one of: " + VALID_DEPRECIATION_METHODS);
    }
    if (!"none".equals(method) && request.inServiceDate() == null) {
        throw new IllegalArgumentException(
                "in_service_date is required when depreciation method is " + method);
    }
    property.setDepreciationMethod(method);
    property.setInServiceDate(request.inServiceDate());
    property.setLandValue(request.landValue());
    if (request.usefulLifeYears() != null) {
        property.setUsefulLifeYears(request.usefulLifeYears());
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd backend && mvn test -pl wealthview-core -Dtest=PropertyServiceTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/property/PropertyService.java \
       backend/wealthview-core/src/test/java/com/wealthview/core/property/PropertyServiceTest.java
git commit -m "fix(core): require in_service_date when depreciation method is not none

Validates that in_service_date is provided when depreciation_method is
straight_line or cost_segregation. Prevents NullPointerException in
DepreciationCalculator.computeStraightLine() which takes a non-nullable
LocalDate parameter."
```

---

## Task 4: Frontend — Depreciation Schedule API + Analytics Tab

**Files:**
- Modify: `frontend/src/api/properties.ts`
- Modify: `frontend/src/types/property.ts`
- Modify: `frontend/src/components/PropertyAnalyticsSection.tsx`

- [ ] **Step 1: Add TypeScript interfaces for the schedule response**

In `frontend/src/types/property.ts`, add:

```typescript
export interface DepreciationScheduleYear {
    tax_year: number;
    annual_depreciation: number;
    cumulative_taken: number;
    remaining_basis: number;
}

export interface DepreciationScheduleResponse {
    depreciation_method: string;
    depreciable_basis: number;
    useful_life_years: number;
    in_service_date: string;
    schedule: DepreciationScheduleYear[];
}
```

- [ ] **Step 2: Add the API function**

In `frontend/src/api/properties.ts`, add:

```typescript
export async function getDepreciationSchedule(propertyId: string): Promise<DepreciationScheduleResponse> {
    const response = await api.get(`/properties/${propertyId}/depreciation-schedule`);
    return response.data;
}
```

Add import for `DepreciationScheduleResponse` from types.

- [ ] **Step 3: Add Depreciation Schedule tab to PropertyAnalyticsSection**

In `PropertyAnalyticsSection.tsx`, after the existing Investment Metrics section, add a new section that:
- Only renders when the property's `depreciation_method !== 'none'`
- Fetches the schedule from `getDepreciationSchedule(propertyId)` via `useEffect`
- Shows a summary header: method, depreciable basis, annual amount
- Renders a table with columns: Tax Year, Annual Depreciation, Cumulative Taken, Remaining Basis
- Highlights the current tax year row (compare with `new Date().getFullYear()`)

The component will need the `propertyId` prop (check if it's already available — the analytics section likely receives the property object or ID).

Format currency values with `toLocaleString()` for readability. Use the same card styling (`cardStyle`) as existing sections.

- [ ] **Step 4: Verify in the browser**

Run: `docker compose up --build -d`
Navigate to a property with depreciation configured (from Task 1). The analytics section should show the depreciation schedule table.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/property.ts \
       frontend/src/api/properties.ts \
       frontend/src/components/PropertyAnalyticsSection.tsx
git commit -m "feat(frontend): add depreciation schedule tab to property analytics

Fetches year-by-year depreciation schedule from the new backend endpoint
and displays it as a table in PropertyAnalyticsSection. Shows depreciable
basis, annual amount, cumulative taken, and remaining basis. Highlights
the current tax year row. Only visible when depreciation is configured."
```

---

## Task 5: Backend — RentalPropertyYearDetail DTO

**Files:**
- Create: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/RentalPropertyYearDetail.java`

- [ ] **Step 1: Create the record**

```java
package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RentalPropertyYearDetail(
        UUID incomeSourceId,
        String propertyName,
        String taxTreatment,
        BigDecimal grossRent,
        BigDecimal operatingExpenses,
        BigDecimal mortgageInterest,
        BigDecimal propertyTax,
        BigDecimal depreciation,
        BigDecimal netTaxableIncome,
        BigDecimal lossAppliedToIncome,
        BigDecimal lossSuspended,
        BigDecimal suspendedLossCarryforward,
        BigDecimal cashFlow
) {}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd backend && mvn compile -pl wealthview-core`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/RentalPropertyYearDetail.java
git commit -m "feat(core): add RentalPropertyYearDetail record for per-property projection breakdown

Per-property per-year rental breakdown including gross rent, expenses,
depreciation, taxable income, loss applied, suspended loss, and cash
flow. Used by the projection engine to provide per-property transparency."
```

---

## Task 6: Backend — IncomeSourceProcessor Per-Property Details

**Files:**
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java`
- Test: `backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `IncomeSourceProcessorTest.java`:

Note: Existing tests use `"active_participation"` as tax treatment, but `RentalLossCalculator` uses `"rental_active_reps"`, `"rental_active_str"`, `"rental_passive"`. Use the correct values from `RentalLossCalculator` in new tests.

```java
@Test
void process_rentalProperty_populatesRentalPropertyDetails() {
    var rentalId = UUID.randomUUID();
    var lossResult = new RentalLossCalculator.LossResult(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10800.0000"));
    when(rentalLossCalculator.applyLossRules(any(), eq("rental_active_reps"), any(), any(), any()))
            .thenReturn(lossResult);

    var rental = new ProjectionIncomeSourceInput(
            rentalId, "123 Main St Rental", "rental_property",
            new BigDecimal("24000"), 65, null,
            BigDecimal.ZERO, false, "rental_active_reps",
            new BigDecimal("3600"), new BigDecimal("9600"),
            null, null, "straight_line",
            Map.of(2026, new BigDecimal("14545")));

    var result = processor.process(
            List.of(rental), 65, 1, 2026,
            BigDecimal.ZERO, "single", BigDecimal.ZERO);

    assertThat(result.rentalPropertyDetails()).hasSize(1);
    var detail = result.rentalPropertyDetails().get(0);
    assertThat(detail.incomeSourceId()).isEqualTo(rentalId);
    assertThat(detail.propertyName()).isEqualTo("123 Main St Rental");
    assertThat(detail.taxTreatment()).isEqualTo("rental_active_reps");
    assertThat(detail.grossRent()).isEqualByComparingTo("24000");
    assertThat(detail.depreciation()).isEqualByComparingTo("14545");
}

@Test
void process_multipleRentalProperties_collectsAllDetails() {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var lossResult = new RentalLossCalculator.LossResult(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000"));
    when(rentalLossCalculator.applyLossRules(any(), any(), any(), any(), any()))
            .thenReturn(lossResult);

    var rental1 = new ProjectionIncomeSourceInput(
            id1, "Property A", "rental_property",
            new BigDecimal("24000"), 65, null,
            BigDecimal.ZERO, false, "rental_passive",
            null, null, null, null, null, null);
    var rental2 = new ProjectionIncomeSourceInput(
            id2, "Property B", "rental_property",
            new BigDecimal("36000"), 65, null,
            BigDecimal.ZERO, false, "rental_active_str",
            null, null, null, null, null, null);

    var result = processor.process(
            List.of(rental1, rental2), 65, 1, 2026,
            BigDecimal.ZERO, "single", BigDecimal.ZERO);

    assertThat(result.rentalPropertyDetails()).hasSize(2);
    assertThat(result.rentalPropertyDetails().get(0).propertyName()).isEqualTo("Property A");
    assertThat(result.rentalPropertyDetails().get(1).propertyName()).isEqualTo("Property B");
}

@Test
void process_noRentalProperties_returnsEmptyDetails() {
    var pensionId = UUID.randomUUID();
    var pension = new ProjectionIncomeSourceInput(
            pensionId, "Pension", "pension",
            new BigDecimal("30000"), 65, null,
            BigDecimal.ZERO, false, "taxable",
            null, null, null, null, null, null);

    var result = processor.process(
            List.of(pension), 65, 1, 2026,
            BigDecimal.ZERO, "single", BigDecimal.ZERO);

    assertThat(result.rentalPropertyDetails()).isEmpty();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=IncomeSourceProcessorTest`
Expected: Compilation error — `rentalPropertyDetails()` doesn't exist on `IncomeSourceYearResult`.

- [ ] **Step 3: Add rentalPropertyDetails to IncomeSourceYearResult**

In `IncomeSourceProcessor.java`, modify the `IncomeSourceYearResult` record (line ~35):

```java
record IncomeSourceYearResult(
        BigDecimal totalCashInflow,
        BigDecimal totalTaxableIncome,
        BigDecimal rentalIncomeGross,
        BigDecimal rentalExpensesTotal,
        BigDecimal depreciationTotal,
        BigDecimal rentalLossApplied,
        BigDecimal suspendedLossCarryforward,
        BigDecimal socialSecurityTaxable,
        BigDecimal selfEmploymentTax,
        Map<String, BigDecimal> incomeBySource,
        List<RentalPropertyYearDetail> rentalPropertyDetails
) {}
```

Add import for `RentalPropertyYearDetail` and `List`.

- [ ] **Step 4: Expand RentalResult to carry source metadata**

Modify the `RentalResult` record (line ~150):

```java
private record RentalResult(
        BigDecimal cashFlow, BigDecimal taxableIncome, BigDecimal expenses,
        BigDecimal depreciation, BigDecimal lossApplied, BigDecimal newSuspendedLoss,
        UUID incomeSourceId, String propertyName, String taxTreatment,
        BigDecimal grossRent, BigDecimal mortgageInterest, BigDecimal propertyTax,
        BigDecimal operatingExpenses
) {}
```

- [ ] **Step 5: Update processRentalProperty to populate new fields**

In `processRentalProperty()`, update the `RentalResult` construction to include the source metadata. **Important:** The `expenses` variable in `processRentalProperty` is already multiplied by `transitionMultiplier` (line 166-167). Store the already-multiplied component values in `RentalResult` to avoid double-multiplying:

```java
// The expenses variable already includes transitionMultiplier. Store individual
// components with the multiplier already applied.
BigDecimal scaledOpExp = opExp.multiply(transitionMultiplier).setScale(SCALE, ROUNDING);
BigDecimal scaledMortInt = mortInt.multiply(transitionMultiplier).setScale(SCALE, ROUNDING);
BigDecimal scaledPropTax = propTax.multiply(transitionMultiplier).setScale(SCALE, ROUNDING);

return new RentalResult(cashFlow, lossResult.netTaxableIncome(), expenses,
        depreciation, lossResult.lossAppliedToIncome(), lossResult.lossSuspended(),
        source.id(), source.name(), source.taxTreatment(),
        nominal, scaledMortInt, scaledPropTax, scaledOpExp);
```

- [ ] **Step 6: Collect RentalPropertyYearDetail in process()**

In the `process()` method, add a list to collect details and build them from each rental result:

```java
List<RentalPropertyYearDetail> rentalDetails = new ArrayList<>();
```

In the `"rental_property"` switch case, after processing the rental result, add:

```java
// Note: RentalResult values are already multiplied by transitionMultiplier.
// suspendedLossCarryforward here is the per-property loss that was suspended
// (rental.newSuspendedLoss), NOT the cumulative pool. See spec Known Limitations.
rentalDetails.add(new RentalPropertyYearDetail(
        rental.incomeSourceId(), rental.propertyName(), rental.taxTreatment(),
        rental.grossRent(),
        rental.operatingExpenses(),  // already multiplied
        rental.mortgageInterest(),   // already multiplied
        rental.propertyTax(),        // already multiplied
        rental.depreciation(), rental.taxableIncome(),
        rental.lossApplied(),
        rental.newSuspendedLoss(),   // this property's contribution to suspended pool
        rental.newSuspendedLoss(),   // per-property suspended = same as lossSuspended for now
        rental.cashFlow()));
```

**Note on `suspendedLossCarryforward`:** The spec acknowledges that per-property suspended loss tracking is a known limitation. The current engine uses a single shared pool. Here we set both `lossSuspended` and `suspendedLossCarryforward` to `rental.newSuspendedLoss()` — the amount this property contributed to the pool this year. The aggregate `suspendedLossCarryforward` on `ProjectionYearDto` remains the authoritative total.

Update the empty/null return paths to include `List.of()` for `rentalPropertyDetails`.

Pass `rentalDetails` to the `IncomeSourceYearResult` constructor.

- [ ] **Step 7: Fix all existing tests that construct IncomeSourceYearResult**

The record now has an extra field. Update all test assertions and any direct constructions of `IncomeSourceYearResult` to include the new `rentalPropertyDetails` field.

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd backend && mvn test -pl wealthview-projection -Dtest=IncomeSourceProcessorTest`
Expected: All tests pass (old and new).

- [ ] **Step 9: Commit**

```bash
git add backend/wealthview-projection/src/main/java/com/wealthview/projection/IncomeSourceProcessor.java \
       backend/wealthview-projection/src/test/java/com/wealthview/projection/IncomeSourceProcessorTest.java
git commit -m "feat(projection): collect per-property rental details in IncomeSourceProcessor

Each rental income source now produces a RentalPropertyYearDetail with
gross rent, expenses, depreciation, taxable income, loss applied,
suspended loss, and cash flow. Collected into a list on the year result
for downstream transparency in ProjectionYearDto."
```

---

## Task 7: Backend — Add rentalPropertyDetails to ProjectionYearDto + Engine + PoolStrategy

**Files:**
- Modify: `backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionYearDto.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java`
- Modify: `backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java`

- [ ] **Step 1: Add field to ProjectionYearDto**

In `ProjectionYearDto.java`, add a new parameter after `withdrawalFromRoth`:

```java
List<RentalPropertyYearDetail> rentalPropertyDetails
```

Add the import for `RentalPropertyYearDetail` and `List`.

Update the `simple()` factory method to pass `null` for the new field.

- [ ] **Step 2: Update all ProjectionYearDto constructors in the engine**

In `DeterministicProjectionEngine.java`, there are multiple places where `ProjectionYearDto` is constructed. Each needs the new `rentalPropertyDetails` field as the last parameter.

**applyIncomeSourceFields()** (line ~627): This is where income source results flow in. Pass `isResult.rentalPropertyDetails()`:

```java
return new ProjectionYearDto(
        ...,
        base.withdrawalFromTaxable(), base.withdrawalFromTraditional(), base.withdrawalFromRoth(),
        isResult.rentalPropertyDetails().isEmpty() ? null : isResult.rentalPropertyDetails());
```

**All other construction sites** (applySurplusReinvested, applyViability, applyPropertyEquity, etc.): Pass `base.rentalPropertyDetails()` to preserve the value through transformations.

Search `DeterministicProjectionEngine.java` for `new ProjectionYearDto(` to find all construction sites. Each one needs the new parameter added at the end.

**CRITICAL: Also update `PoolStrategy.java`** — this file has two `ProjectionYearDto` construction sites that will fail to compile:

1. `SinglePool.buildYearDto()` (line ~174): Uses `ProjectionYearDto.simple()` — this is covered by updating the `simple()` factory method above.
2. `MultiPool.buildYearDto()` (line ~402): Constructs `ProjectionYearDto` directly with positional args. Add `null` as the last parameter (pools don't produce rental details; those come from `applyIncomeSourceFields()`):

```java
// At line ~416, before the closing paren:
                    withdrawalFromRoth.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromRoth : null,
                    null);  // rentalPropertyDetails — populated later by applyIncomeSourceFields
```

- [ ] **Step 3: Verify compilation**

Run: `cd backend && mvn compile -pl wealthview-core,wealthview-projection`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run all backend tests**

Run: `cd backend && mvn test -pl wealthview-core,wealthview-projection`
Expected: All tests pass. Some tests may need updating if they construct `ProjectionYearDto` directly.

- [ ] **Step 5: Commit**

```bash
git add backend/wealthview-core/src/main/java/com/wealthview/core/projection/dto/ProjectionYearDto.java \
       backend/wealthview-projection/src/main/java/com/wealthview/projection/DeterministicProjectionEngine.java \
       backend/wealthview-projection/src/main/java/com/wealthview/projection/PoolStrategy.java
git commit -m "feat(projection,core): pass per-property rental details through ProjectionYearDto

Adds rentalPropertyDetails list to ProjectionYearDto. The engine passes
the per-property details collected by IncomeSourceProcessor through all
DTO construction sites including PoolStrategy.SinglePool and MultiPool.
Null when no rental income sources are present."
```

---

## Task 8: Frontend — Per-Property Expandable Rows in Income Tax Tab

**Files:**
- Modify: `frontend/src/types/projection.ts`
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx`

- [ ] **Step 1: Add TypeScript interfaces**

In `frontend/src/types/projection.ts`, add:

```typescript
export interface RentalPropertyYearDetail {
    income_source_id: string;
    property_name: string;
    tax_treatment: string;
    gross_rent: number;
    operating_expenses: number;
    mortgage_interest: number;
    property_tax: number;
    depreciation: number;
    net_taxable_income: number;
    loss_applied_to_income: number;
    loss_suspended: number;
    suspended_loss_carryforward: number;
    cash_flow: number;
}
```

Add to the `ProjectionYear` interface:

```typescript
rental_property_details: RentalPropertyYearDetail[] | null;
```

- [ ] **Step 2: Add expandable row state to ProjectionDetailPage**

In `ProjectionDetailPage.tsx`, add state for tracking which years are expanded:

```typescript
const [expandedYears, setExpandedYears] = useState<Set<number>>(new Set());

const toggleYear = (year: number) => {
    setExpandedYears(prev => {
        const next = new Set(prev);
        if (next.has(year)) next.delete(year);
        else next.add(year);
        return next;
    });
};
```

- [ ] **Step 3: Add expand/collapse to Income Tax tab rows**

In the Income Tax tab table body (line ~430), modify each row:
- If `y.rental_property_details && y.rental_property_details.length > 0`, show a clickable chevron in the first column
- When expanded, render additional rows below with per-property detail

```tsx
{result.yearly_data.filter(y => y.retired).map(y => (
  <React.Fragment key={y.year}>
    <tr style={{ borderBottom: '1px solid #f0f0f0', background: '#fff8e1', cursor: y.rental_property_details?.length ? 'pointer' : 'default' }}
        onClick={() => y.rental_property_details?.length && toggleYear(y.year)}>
      <td style={{ padding: '0.4rem 0.75rem' }}>
        {y.rental_property_details?.length ? (expandedYears.has(y.year) ? '▼' : '▶') : ''} {y.year}
      </td>
      {/* ... existing columns ... */}
    </tr>
    {expandedYears.has(y.year) && y.rental_property_details?.map(d => (
      <tr key={d.income_source_id} style={{ background: '#f5f5f5', fontSize: '0.85rem' }}>
        <td style={{ padding: '0.3rem 0.75rem', paddingLeft: '2rem' }}>{d.property_name}</td>
        <td style={{ padding: '0.3rem 0.75rem' }}>
          <span style={{
            fontSize: '0.7rem', padding: '2px 6px', borderRadius: 4,
            background: d.tax_treatment === 'rental_passive' ? '#e0e0e0' :
                         d.tax_treatment === 'rental_active_reps' ? '#c8e6c9' : '#bbdefb',
            color: '#333'
          }}>
            {d.tax_treatment === 'rental_passive' ? 'Passive' :
             d.tax_treatment === 'rental_active_reps' ? 'REPS' : 'STR'}
          </span>
        </td>
        <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right' }}>{formatCurrency(d.gross_rent)}</td>
        <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right' }}>{formatCurrency(d.operating_expenses + d.mortgage_interest + d.property_tax)}</td>
        <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right', color: '#7b1fa2' }}>{formatCurrency(d.depreciation)}</td>
        <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right' }}>{d.loss_applied_to_income > 0 ? formatCurrency(d.loss_applied_to_income) : '-'}</td>
        <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right' }}>{d.suspended_loss_carryforward > 0 ? formatCurrency(d.suspended_loss_carryforward) : '-'}</td>
        <td colSpan={3}></td>
      </tr>
    ))}
  </React.Fragment>
))}
```

Adapt column structure and styling to match the existing table exactly.

- [ ] **Step 4: Verify in the browser**

Run: `docker compose up --build -d`
Run a projection with a rental property income source. Go to Income Tax tab. Click a year row to expand and see per-property detail.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/projection.ts \
       frontend/src/pages/ProjectionDetailPage.tsx
git commit -m "feat(frontend): add expandable per-property rental detail in Income Tax tab

Each projection year row with rental properties gets a clickable chevron.
Expanding shows per-property breakdown: name, tax treatment badge (Passive/
REPS/STR), gross rent, expenses, depreciation, loss applied, and
suspended loss carryforward."
```

---

## Task 9: Frontend — Depreciation Tax Shield Summary

**Files:**
- Modify: `frontend/src/pages/ProjectionDetailPage.tsx`

- [ ] **Step 1: Add a new tab for Tax Shield**

Add `'tax_shield'` to the `TabId` type (line 19):

```typescript
type TabId = 'chart' | 'flows' | 'table' | 'spending' | 'income_tax' | 'income_streams' | 'tax_shield';
```

Add a tab button after the Income Tax tab button.

- [ ] **Step 2: Compute summary metrics from per-year data**

Add a `useMemo` hook that aggregates the tax shield summary from `result.yearly_data`:

```typescript
const taxShieldSummary = useMemo(() => {
    if (!result?.yearly_data) return null;
    const years = result.yearly_data.filter(y => y.retired);

    let totalDepreciation = 0;
    let totalLossApplied = 0;
    let estimatedTaxSavings = 0;
    let rothConversionSheltered = 0;
    const perProperty: Record<string, { name: string; taxTreatment: string; depreciation: number; lossApplied: number }> = {};

    for (const y of years) {
        const dep = y.depreciation_total || 0;
        const loss = y.rental_loss_applied || 0;
        totalDepreciation += dep;
        totalLossApplied += loss;

        // Estimated tax savings using effective rate (approximate)
        if (loss > 0 && y.tax_liability != null && y.tax_liability > 0) {
            const taxableIncome = (y.income_streams_total || 0) + (y.roth_conversion_amount || 0);
            const effectiveRate = taxableIncome > 0 ? y.tax_liability / taxableIncome : 0;
            estimatedTaxSavings += loss * effectiveRate;
        }

        // Roth conversion sheltered (approximate)
        if (loss > 0 && y.roth_conversion_amount && y.roth_conversion_amount > 0) {
            rothConversionSheltered += Math.min(loss, y.roth_conversion_amount);
        }

        // Per-property aggregation
        if (y.rental_property_details) {
            for (const d of y.rental_property_details) {
                const key = d.income_source_id;
                if (!perProperty[key]) {
                    perProperty[key] = { name: d.property_name, taxTreatment: d.tax_treatment, depreciation: 0, lossApplied: 0 };
                }
                perProperty[key].depreciation += d.depreciation;
                perProperty[key].lossApplied += d.loss_applied_to_income;
            }
        }
    }

    const suspendedLossRemaining = years.length > 0
        ? (years[years.length - 1].suspended_loss_carryforward || 0)
        : 0;

    return {
        totalDepreciation, totalLossApplied, estimatedTaxSavings,
        rothConversionSheltered, suspendedLossRemaining,
        perProperty: Object.values(perProperty),
    };
}, [result]);
```

- [ ] **Step 3: Render the Tax Shield tab content**

```tsx
{activeTab === 'tax_shield' && taxShieldSummary && (
  <div style={{ padding: '1rem' }}>
    <h3>Depreciation Tax Shield Summary</h3>
    <p style={{ fontSize: '0.85rem', color: '#666' }}>
      Values marked (approx.) are estimates. See spec for methodology.
    </p>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1rem', marginTop: '1rem' }}>
      <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8 }}>
        <div style={{ fontSize: '0.85rem', color: '#666' }}>Total Depreciation Taken</div>
        <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(taxShieldSummary.totalDepreciation)}</div>
      </div>
      <div style={{ padding: '1rem', background: '#f5f5f5', borderRadius: 8 }}>
        <div style={{ fontSize: '0.85rem', color: '#666' }}>Total Loss Applied to Income</div>
        <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(taxShieldSummary.totalLossApplied)}</div>
      </div>
      <div style={{ padding: '1rem', background: '#e8f5e9', borderRadius: 8 }}>
        <div style={{ fontSize: '0.85rem', color: '#666' }}>Estimated Tax Savings (approx.)</div>
        <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#2e7d32' }}>{formatCurrency(taxShieldSummary.estimatedTaxSavings)}</div>
      </div>
      <div style={{ padding: '1rem', background: '#e3f2fd', borderRadius: 8 }}>
        <div style={{ fontSize: '0.85rem', color: '#666' }}>Roth Conversion Sheltered (approx.)</div>
        <div style={{ fontSize: '1.5rem', fontWeight: 600, color: '#1565c0' }}>{formatCurrency(taxShieldSummary.rothConversionSheltered)}</div>
      </div>
      <div style={{ padding: '1rem', background: '#fff3e0', borderRadius: 8 }}>
        <div style={{ fontSize: '0.85rem', color: '#666' }}>Suspended Losses Remaining</div>
        <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{formatCurrency(taxShieldSummary.suspendedLossRemaining)}</div>
      </div>
    </div>

    {/* Per-property subtotals */}
    {taxShieldSummary.perProperty.length > 0 && (
      <div style={{ marginTop: '1.5rem' }}>
        <h4>Per-Property Breakdown</h4>
        <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: '0.5rem' }}>
          <thead>
            <tr style={{ background: '#fafafa', borderBottom: '2px solid #ddd' }}>
              <th style={{ textAlign: 'left', padding: '0.5rem' }}>Property</th>
              <th style={{ textAlign: 'left', padding: '0.5rem' }}>Classification</th>
              <th style={{ textAlign: 'right', padding: '0.5rem' }}>Total Depreciation</th>
              <th style={{ textAlign: 'right', padding: '0.5rem' }}>Total Loss Applied</th>
            </tr>
          </thead>
          <tbody>
            {taxShieldSummary.perProperty.map((p, i) => (
              <tr key={i} style={{ borderBottom: '1px solid #eee' }}>
                <td style={{ padding: '0.5rem' }}>{p.name}</td>
                <td style={{ padding: '0.5rem' }}>
                  <span style={{
                    fontSize: '0.75rem', padding: '2px 6px', borderRadius: 4,
                    background: p.taxTreatment === 'rental_passive' ? '#e0e0e0' :
                                 p.taxTreatment === 'rental_active_reps' ? '#c8e6c9' : '#bbdefb'
                  }}>
                    {p.taxTreatment === 'rental_passive' ? 'Passive' :
                     p.taxTreatment === 'rental_active_reps' ? 'REPS' : 'STR'}
                  </span>
                </td>
                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(p.depreciation)}</td>
                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(p.lossApplied)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}
  </div>
)}
```

Only show the Tax Shield tab button when there's rental/depreciation data (check `taxShieldSummary.totalDepreciation > 0`).

- [ ] **Step 4: Verify in the browser**

Run: `docker compose up --build -d`
Run a projection with REPS/STR rental properties and Roth conversions. The Tax Shield tab should show the summary metrics and per-property breakdown.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/ProjectionDetailPage.tsx
git commit -m "feat(frontend): add Depreciation Tax Shield summary tab to projection results

Shows total depreciation taken, total loss applied, estimated tax savings
(approximate), Roth conversion sheltered (approximate), and suspended
losses remaining. Includes per-property breakdown with tax treatment
badges. All metrics aggregated client-side from per-year projection data."
```

---

## Task 10: End-to-End Verification

- [ ] **Step 1: Run all backend tests**

Run: `cd backend && mvn clean test`
Expected: All tests pass.

- [ ] **Step 2: Run frontend tests**

Run: `cd frontend && npm run test`
Expected: All tests pass (or no regressions).

- [ ] **Step 3: Full Docker integration test**

Run: `docker compose down && docker compose up --build -d`

Test the full flow:
1. Log in as demo@wealthview.local / demo123
2. Navigate to a property → verify Depreciation section appears in the form
3. Set depreciation method to Straight-Line, enter land value, verify computed values
4. Save the property
5. Check the analytics section → verify Depreciation Schedule tab shows year-by-year table
6. Navigate to a projection scenario with a rental income source linked to that property
7. Run the projection
8. Go to Income Tax tab → verify per-property expandable rows work
9. Go to Tax Shield tab → verify summary metrics appear
10. If REPS/STR classification is set on the income source, verify the loss applied numbers are correct

- [ ] **Step 4: Commit any final fixes**

If any adjustments were needed during verification, commit them.
