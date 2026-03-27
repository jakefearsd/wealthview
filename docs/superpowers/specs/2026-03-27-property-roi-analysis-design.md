# Property ROI Analysis — Hold vs. Sell Comparison

## Summary

Add a per-income-source ROI analysis to the property detail page that compares
**holding and renting** against **selling and investing the proceeds**. Each
income source linked to an investment property gets its own analysis card with
tunable assumptions, showing ending net worth for both paths and the delta.

## Motivation

The existing property analytics (cap rate, NOI, cash-on-cash return) describe
current-year operating performance but don't answer the long-term planning
question: "Am I better off keeping this rental or liquidating into the market?"
This feature gives the user a simple, assumption-driven comparison to inform
that decision.

## Design Decisions

- **Lives on income sources, not a separate page.** Each income source linked to
  a property defines a "hold at this rent" scenario. Multiple income sources on
  the same property give a natural side-by-side comparison of rental strategies.
- **Backend-computed.** Financial math stays in Java with BigDecimal precision,
  consistent with PropertyAnalyticsService. Frontend renders cards.
- **Summary card output, not year-by-year table.** Keeps the UI clean; the user
  sees ending net worth for each path and the delta.
- **No hypothetical/draft income source tagging.** Users manage which income
  sources are "real" vs. exploratory themselves. Keep it simple.
- **Property appreciation uses the existing `annual_appreciation_rate` field.**
  No override in the comparison form.

## Hold Scenario (per income source, projected N years)

- **Rental income:** Starts at the income source's annual amount, grows at
  user-specified rent growth rate, compounded annually.
- **Expenses:** Current annual property tax + insurance + maintenance from the
  property entity, inflated at user-specified expense inflation rate.
- **Mortgage:** Continues amortization from current position using existing loan
  details (amount, rate, term, start date). Drops to zero after payoff.
- **Property value:** Compounds from current value at the property's
  `annual_appreciation_rate`.
- **Ending net worth:** Projected property value − remaining mortgage balance +
  cumulative net cash flow (rent − expenses − mortgage payments, accumulated
  but not invested).

## Sell Scenario

- **Gross proceeds:** Current property value.
- **Selling costs:** 6% of gross proceeds (hardcoded default).
- **Depreciation recapture:** Accumulated depreciation (from in-service date to
  today) × 25% (Section 1250). Uses the property's existing depreciation
  schedule data (method, useful life, cost seg allocations, in-service date,
  land value). The depreciation calculation reuses the same logic that produces
  the depreciation schedule displayed on the property detail page.
- **Capital gains tax:** (Current value − purchase price − selling costs −
  accumulated depreciation) × 15% long-term capital gains rate.
- **Net proceeds:** Gross − selling costs − recapture tax − capital gains tax.
- **Investment growth:** Net proceeds compounded at user-specified annual return
  rate over N years.
- **Ending net worth:** Compounded investment value.

## API

### Endpoint

`GET /api/properties/{propertyId}/income-sources/{sourceId}/roi-analysis`

### Query Parameters

| Param              | Type       | Default | Description                      |
|--------------------|------------|---------|----------------------------------|
| `years`            | int        | 10      | Comparison period in years       |
| `investment_return`| BigDecimal | 0.07    | Annual return on sale proceeds   |
| `rent_growth`      | BigDecimal | 0.03    | Annual rent increase rate        |
| `expense_inflation`| BigDecimal | 0.03    | Annual expense increase rate     |

### Response

```json
{
  "income_source_name": "Rental Income",
  "annual_rent": 24000.00,
  "comparison_years": 10,
  "hold": {
    "ending_property_value": 485000.00,
    "ending_mortgage_balance": 120000.00,
    "cumulative_net_cash_flow": 45000.00,
    "ending_net_worth": 410000.00
  },
  "sell": {
    "gross_proceeds": 350000.00,
    "selling_costs": 21000.00,
    "depreciation_recapture_tax": 8750.00,
    "capital_gains_tax": 12000.00,
    "net_proceeds": 308250.00,
    "ending_net_worth": 606123.00
  },
  "advantage": "sell",
  "advantage_amount": 196123.00
}
```

## Backend

### New Service: `PropertyRoiService`

Lives in `wealthview-core` package `com.wealthview.core.property`. Depends on:
- `PropertyRepository` — property entity with appreciation rate, expenses,
  loan details, depreciation config
- `IncomeSourceRepository` — rental amount for the specified income source
- `AmortizationCalculator` — existing utility for mortgage math
- Depreciation schedule logic — existing in PropertyAnalyticsService / related
  classes for accumulated depreciation calculation

Key method:
```java
public RoiAnalysisResponse computeRoiAnalysis(
    UUID tenantId, UUID propertyId, UUID incomeSourceId,
    int years, BigDecimal investmentReturn,
    BigDecimal rentGrowth, BigDecimal expenseInflation)
```

### Controller

New method on existing `PropertyController`:
```java
@GetMapping("/{propertyId}/income-sources/{sourceId}/roi-analysis")
public RoiAnalysisResponse getRoiAnalysis(...)
```

### DTOs

New records in `com.wealthview.core.property.dto`:
- `RoiAnalysisResponse` — top-level response
- `HoldScenarioResult` — hold side fields
- `SellScenarioResult` — sell side fields

## Frontend

### New Component: `PropertyRoiCard.tsx`

Rendered on `PropertyDetailPage.tsx` after `PropertyAnalyticsSection`, only for
`investment` type properties with at least one linked income source.

**Card layout:**
- **Header:** Income source name and current annual rent
- **Inputs row:** Four compact fields:
  - Years (dropdown: 5 / 10 / 15 / 20)
  - Investment Return % (number input)
  - Rent Growth % (number input)
  - Expense Inflation % (number input)
- **Results:** Two side-by-side columns:
  - **Hold:** ending property value, remaining mortgage, cumulative cash flow,
    ending net worth (bold)
  - **Sell:** net proceeds, investment growth, ending net worth (bold)
- **Bottom line:** "Holding is better by $X" or "Selling is better by $X"
  colored green/red

**Behavior:** Changing any input re-fetches the analysis (debounced). Each
income source card makes its own API call.

### API Client

New function in `frontend/src/api/properties.ts`:
```typescript
export function getRoiAnalysis(
  propertyId: string,
  sourceId: string,
  params: { years: number; investmentReturn: number; rentGrowth: number; expenseInflation: number }
): Promise<RoiAnalysisResponse>
```

### Types

New interfaces in `frontend/src/types/property.ts` matching the API response.

## Out of Scope

- Year-by-year detail table (could add later as expandable section)
- Editable selling cost percentage (hardcoded 6%)
- Editable capital gains / recapture tax rates
- Draft/hypothetical income source tagging
- Property comparison across multiple properties
