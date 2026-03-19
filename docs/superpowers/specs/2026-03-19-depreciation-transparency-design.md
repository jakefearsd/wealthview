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
- **Depreciation End Year:** `in_service_date.year + ceil(useful_life_years)`

### Validation

- If `land_value >= purchase_price`, show warning: "Land value must be less than purchase price for depreciation."
- When depreciation method is "None", the other fields are hidden/disabled.
- `useful_life_years` must be > 0.

### API

No backend changes needed. The fields already exist on the property entity and are accepted by the create/update endpoints. The form simply needs to send them.

---

## Section 2: Property Analytics — Depreciation Schedule Tab

Add a "Depreciation Schedule" tab to `PropertyAnalyticsSection.tsx`.

### New Endpoint

`GET /api/properties/{id}/depreciation-schedule`

**Response:**

```json
{
  "depreciation_method": "straight_line",
  "depreciable_basis": 400000,
  "useful_life_years": 27.5,
  "in_service_date": "2024-06-15",
  "schedule": [
    {
      "tax_year": 2024,
      "annual_depreciation": 7272.73,
      "cumulative_taken": 7272.73,
      "remaining_basis": 392727.27
    },
    {
      "tax_year": 2025,
      "annual_depreciation": 14545.45,
      "cumulative_taken": 21818.18,
      "remaining_basis": 378181.82
    }
  ]
}
```

### Backend

- New `DepreciationScheduleResponse` record in `wealthview-api` with nested `DepreciationScheduleYearResponse` record.
- New controller method on `PropertyController` that calls `DepreciationCalculator.computeStraightLine()` and assembles the response with cumulative/remaining columns.
- Service method on `PropertyService` (or a dedicated method) that loads the property, validates depreciation is configured, and delegates to the calculator.

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
    UUID propertyId,
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

### Backend Changes

- `IncomeSourceProcessor.RentalResult` expanded to carry source ID, property name, and tax treatment.
- `IncomeSourceProcessor.IncomeSourceYearResult` gains a `List<RentalPropertyYearDetail>` field.
- `processRentalProperty()` builds a `RentalPropertyYearDetail` from each rental result.
- `ProjectionYearDto` gains a `List<RentalPropertyYearDetail> rentalPropertyDetails` field.
- Existing aggregate fields (`depreciationTotal`, `rentalLossApplied`, `rentalExpensesTotal`, `suspendedLossCarryforward`) remain unchanged for backward compatibility and summary display.

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
| Estimated Tax Savings | For each year: `loss_applied * marginal_rate`, summed | Per-year data + `tax_liability` / taxable income |
| Roth Conversion Sheltered | For each year with Roth conversions: `min(rental_loss_applied, roth_conversion_amount)` | Per-year `rental_loss_applied` + `roth_conversion_amount` |
| Suspended Losses Remaining | Final year's `suspended_loss_carryforward` | Last projection year |

### Per-Property Subtotals

Within the summary, show each property's contribution:

- Property name
- Total depreciation taken (its share)
- Total loss applied (its share)
- Tax treatment classification

### Implementation

- **Client-side aggregation.** All required data is in the per-year projection response. No new backend computation needed.
- Roth Conversion Sheltered uses `min(rentalLossApplied, rothConversionAmount)` per year as a reasonable approximation. The rental loss offsets ordinary income, which includes the Roth conversion — this captures the overlap without requiring the engine to track the exact attribution.
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
