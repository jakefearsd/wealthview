# Depreciation Transparency & Input Design

**Date:** 2026-03-19
**Status:** Approved
**Scope:** Surface depreciation inputs on property form, add depreciation schedule to property analytics, add per-property rental breakdown to projection output, add depreciation tax shield summary.

---

## Problem

The property entity has depreciation fields (`land_value`, `in_service_date`, `depreciation_method`, `useful_life_years`) in the database, but they are not exposed in the frontend UI. Users cannot enter, view, or verify depreciation assumptions. This makes it impossible to test or trust the projection engine's depreciation calculations, particularly for REPS and STR scenarios where depreciation losses can shelter Roth conversion income.

## Design Decisions

- **All property types** get depreciation inputs (not just investment). Covers edge cases like home office deduction and future rental conversion.
- **Tax treatment classification** (passive/REPS/STR) stays on the income source, not the property. Matches IRS activity-level classification.
- **Per-property breakdown** in projection output, not just aggregates. Required for verifying math.
- **No cost segregation study UI** in this phase. Only None and Straight-Line methods are offered in the form. Cost Segregation remains in the data model for future use.
- **Depreciation schedule** computed server-side via existing `DepreciationCalculator` to avoid duplicating mid-month convention logic in JavaScript.
- **Tax shield summary** aggregated client-side from per-year projection data, except Roth sheltering which uses `min(rentalLossApplied, rothConversionAmount)` as approximation.

---

## Section 1: Property Form — Depreciation Section

Add a collapsible "Depreciation" section to `PropertyForm.tsx`, positioned after "Financial Assumptions."

### Fields

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| Depreciation Method | Dropdown: None, Straight-Line | None | Cost Segregation exists in DB but not offered in UI |
| In-Service Date | Date picker | Falls back to `purchase_date` when user selects Straight-Line and leaves blank | When the property was placed in service for rental/business use |
| Land Value | Currency input | — | Non-depreciable portion of purchase price |
| Useful Life (years) | Numeric input | 27.5 | Override for commercial (39) or other cases |

### Computed Read-Only Display

Shown below the inputs, updates live as user types:

- **Depreciable Basis:** `purchase_price - land_value`
- **Annual Depreciation:** `depreciable_basis / useful_life_years`
- **First Year (prorated):** Computed using IRS mid-month convention from in-service date
- **Depreciation End Year:** Computed from the schedule (last year with a non-zero entry). Not a simple formula due to mid-month convention affecting first/last year proration.

### Validation

- If `land_value >= purchase_price`, show warning: "Land value must be less than purchase price for depreciation."
- When depreciation method is "None", the other fields are hidden/disabled.
- `useful_life_years` must be > 0.
- **`in_service_date` is required when depreciation method is not "none."** The form must populate this field before saving. If the user selects Straight-Line and leaves the date blank, the form auto-fills with `purchase_date` and displays a note: "Defaulted to purchase date." The backend should reject a property with `depreciation_method != "none"` and `in_service_date = null`.

### API

No backend changes needed. The fields already exist on the property entity and are accepted by the create/update endpoints. The form simply needs to send them.

---

## Section 2: Property Analytics — Depreciation Schedule Tab

Add a "Depreciation Schedule" tab to `PropertyAnalyticsSection.tsx`.

### New Endpoint

`GET /api/v1/properties/{id}/depreciation-schedule`

Returns 400 if `depreciation_method` is "none" or depreciation is not configured. Returns 200 with the schedule otherwise.

**Response (200):**

```json
{
  "depreciation_method": "straight_line",
  "depreciable_basis": 400000,
  "useful_life_years": 27.5,
  "in_service_date": "2024-06-15",
  "schedule": [
    {
      "tax_year": 2024,
      "annual_depreciation": 7878.79,
      "cumulative_taken": 7878.79,
      "remaining_basis": 392121.21
    },
    {
      "tax_year": 2025,
      "annual_depreciation": 14545.45,
      "cumulative_taken": 22424.24,
      "remaining_basis": 377575.76
    }
  ]
}
```

_Note: First year prorated via IRS mid-month convention. For a June 15 in-service date, 6.5 remaining months (half of June + July–December), so first year = `14545.45 * 6.5 / 12 = 7878.79`._

### Backend

- **Service layer:** New method on `PropertyService` that loads the property, validates depreciation is configured, calls `DepreciationCalculator.computeStraightLine()`, and returns the raw `Map<Integer, BigDecimal>` schedule along with the property's depreciation metadata (method, basis, useful life, in-service date).
- **API layer:** New `DepreciationScheduleResponse` record in `wealthview-api` with nested `DepreciationScheduleYearResponse` record. The controller method receives the raw map from the service and assembles it into the response DTO with cumulative/remaining columns.
- This follows the existing module boundary pattern: service returns domain data, controller maps to response DTO.

### Frontend

- Tab only shown when `depreciation_method !== "none"`.
- Summary header above table: "27.5-year straight-line | Depreciable Basis: $400,000 | Annual: $14,545"
- Current tax year row highlighted.
- Year-by-year table with columns: Tax Year, Annual Depreciation, Cumulative Taken, Remaining Basis.

---

## Section 3: Per-Property Rental Breakdown in Projection Output

### New DTO

`RentalPropertyYearDetail` record in `wealthview-core`:

```java
record RentalPropertyYearDetail(
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

_Note: Uses `incomeSourceId` (not property entity ID) because the projection operates on income sources. The income source name (e.g., "123 Main St Rental") serves as the property identifier in the UI._

### Backend Changes

- `IncomeSourceProcessor.RentalResult` expanded to carry source ID, source name, and tax treatment.
- `IncomeSourceProcessor.IncomeSourceYearResult` gains a `List<RentalPropertyYearDetail>` field.
- `processRentalProperty()` builds a `RentalPropertyYearDetail` from each rental result.
- `ProjectionYearDto` gains a `List<RentalPropertyYearDetail> rentalPropertyDetails` field.
- Existing aggregate fields (`depreciationTotal`, `rentalLossApplied`, `rentalExpensesTotal`, `suspendedLossCarryforward`) remain unchanged for backward compatibility and summary display.

### Suspended Loss Tracking — Known Limitation

The current `IncomeSourceProcessor` tracks suspended passive losses as a **single aggregate pool** shared across all rental properties (line 107). IRS rules technically track suspended losses per-activity (per-property). For this phase, the per-property `lossSuspended` and `suspendedLossCarryforward` fields on `RentalPropertyYearDetail` will reflect the **per-property contribution to the shared pool** (i.e., how much loss this property generated that was suspended), not true per-property carryforward tracking. The aggregate `suspendedLossCarryforward` on `ProjectionYearDto` remains the authoritative total. Per-property carryforward tracking is a future enhancement that would require refactoring `IncomeSourceProcessor` to maintain a `Map<UUID, BigDecimal>` of suspended losses by income source.

### Data Flow

1. `IncomeSourceProcessor.process()` iterates rental income sources.
2. For each rental source, `processRentalProperty()` returns a `RentalResult` with all per-property fields.
3. Results collected into `List<RentalPropertyYearDetail>` on the year result.
4. `DeterministicProjectionEngine` passes the list through to `ProjectionYearDto`.
5. API serializes to JSON with snake_case field names.

### Frontend Changes

- `ProjectionYear` TypeScript interface gains `rental_property_details: RentalPropertyYearDetail[] | null`.
- New `RentalPropertyYearDetail` TypeScript interface.
- In the Income Tax tab on `ProjectionDetailPage`, projection year rows with rental data get an expandable chevron.
- Expanded view shows per-property detail table: property name, tax treatment badge (Passive / REPS / STR), gross rent, expenses, depreciation, net taxable, loss applied, suspended loss, cash flow.
- Aggregate row remains the primary view; per-property is drill-down only.

---

## Section 4: Depreciation Tax Shield Summary

A summary section on the projection results page showing the cumulative impact of depreciation across the entire projection.

### Metrics

| Metric | Computation | Source |
|--------|-------------|--------|
| Total Depreciation Taken | Sum of `depreciation` across all properties and all projection years | Per-year `rentalPropertyDetails` |
| Total Loss Applied to Income | Sum of `loss_applied_to_income` across all years | Per-year `rentalPropertyDetails` |
| Estimated Tax Savings (approx.) | For each year: `loss_applied * effective_tax_rate`, summed. Uses effective rate (tax_liability / taxable_income) as a practical approximation; true marginal rate would require re-running the tax engine. Label as approximate in the UI. | Per-year data |
| Roth Conversion Sheltered (approx.) | For each year with Roth conversions: `min(rental_loss_applied, roth_conversion_amount)`. This overstates sheltering when other ordinary income exists, since IRS rules reduce AGI overall — the loss does not specifically target conversion income. Label as approximate in the UI. | Per-year `rental_loss_applied` + `roth_conversion_amount` |
| Suspended Losses Remaining | Final year's `suspended_loss_carryforward` | Last projection year |

### Per-Property Subtotals

Within the summary, show each property's contribution:

- Property name
- Total depreciation taken (its share)
- Total loss applied (its share)
- Tax treatment classification

### Implementation

- **Client-side aggregation.** All required data is in the per-year projection response. No new backend computation needed.
- Roth Conversion Sheltered uses `min(rentalLossApplied, rothConversionAmount)` per year as an approximation. This overstates the sheltering effect when other ordinary income exists (the loss reduces AGI overall, not specifically conversion income). The UI should label this metric as "approximate."
- Estimated Tax Savings uses the effective tax rate as an approximation. True marginal savings would require re-running the tax engine without depreciation losses, which is out of scope for this phase. The UI should label as "approximate."
- Rendered as a card/panel near the top of the projection results page or as a dedicated "Tax Shield" tab.

---

## Files to Modify

### Backend
| File | Change |
|------|--------|
| `PropertyController` (wealthview-api) | New depreciation schedule endpoint |
| `PropertyService` (wealthview-core) | Method to compute and return depreciation schedule |
| `IncomeSourceProcessor` (wealthview-projection) | Per-property detail collection in rental processing |
| `ProjectionYearDto` (wealthview-core) | Add `rentalPropertyDetails` list field |
| `DeterministicProjectionEngine` (wealthview-projection) | Pass per-property details through to DTO |

### New Backend Files
| File | Purpose |
|------|---------|
| `DepreciationScheduleResponse` (wealthview-api) | Response DTO for schedule endpoint |
| `RentalPropertyYearDetail` (wealthview-core) | Per-property per-year rental breakdown record |

### Frontend
| File | Change |
|------|--------|
| `PropertyForm.tsx` | Add Depreciation collapsible section |
| `PropertyAnalyticsSection.tsx` | Add Depreciation Schedule tab |
| `ProjectionDetailPage.tsx` | Expandable per-property rows, tax shield summary |
| `types/property.ts` | Already has fields — no change needed |
| `types/projection.ts` | Add `RentalPropertyYearDetail` interface, update `ProjectionYear` |

### No Schema Migration Needed
All depreciation fields already exist in the database (V032). No new columns or tables required.

---

## Known Limitations & Future Work

- **Suspended losses tracked as single pool.** The engine shares one suspended loss total across all rental properties rather than tracking per-activity as IRS rules require. Per-property carryforward is a future enhancement.
- **Tax savings and Roth sheltering are approximations.** Labeled as such in the UI. True marginal analysis would require running the tax engine twice (with and without depreciation) per projection year.
- **No cost segregation study input.** Only None and Straight-Line offered. Cost Segregation schedule entry is a future phase.
- **No property disposition modeling.** When a property is sold, all suspended passive losses are released per IRC Section 469(g). This is not modeled in the current projection engine.
- **No depreciation recapture.** When a property is sold, depreciation taken is recaptured at 25% (Section 1250). Not modeled.
