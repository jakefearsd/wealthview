# Property Income vs Expenses Chart — Design

## Goal

Add a chart to property-linked income source cards on the Income Sources page that shows monthly rental income estimates against actual logged expenses on the linked property. Helps users validate their projection inputs and understand cost structure.

## Chart Type

**Diverging stacked bar chart** — income bars above the zero line, expense bars stacked below by category, 12 months on x-axis.

- **Above zero:** Monthly rent estimate (income source `annual_amount / 12`) as a consistent green bar
- **Below zero:** Actual property expenses stacked by category (mortgage, insurance, tax, maintenance, HOA, capex, mgmt_fee) using distinct colors
- **Net line:** Dashed line showing income minus expenses per month

This gives immediate visual feedback: "My projected rent is $2,200/mo but my costs are $1,650/mo broken down as..."

## Data Source

**New backend endpoint:** `GET /api/v1/properties/{id}/cashflow-detail?from=YYYY-MM&to=YYYY-MM`

Returns monthly data with expense category breakdown:

```json
[
  {
    "month": "2025-07",
    "total_income": 2200.00,
    "expenses_by_category": {
      "mortgage": 1200.00,
      "insurance": 150.00,
      "tax": 300.00
    },
    "total_expenses": 1650.00,
    "net_cash_flow": 550.00
  }
]
```

Reuses the existing `spreadEntry()` logic in `PropertyService` but groups expenses by category instead of summing them. Income is included as a total (category breakdown is less useful since it's mostly "rent").

## Frontend Component

New `PropertyIncomeChart.tsx` in `frontend/src/components/`. Rendered inside income source cards on `IncomeSourcesPage` when `property_id` is set.

The chart shows the trailing 12 months from today. Uses Recharts `BarChart` with stacked bars (expenses negated) and a `Line` overlay for net. Follows existing chart styling patterns (ResponsiveContainer, cardStyle, Material Design colors).

## Expense Category Colors

| Category | Color | Hex |
|----------|-------|-----|
| mortgage | Blue | #1976d2 |
| tax | Orange | #ed6c02 |
| insurance | Purple | #9c27b0 |
| maintenance | Teal | #0097a7 |
| hoa | Brown | #795548 |
| capex | Indigo | #3f51b5 |
| mgmt_fee | Blue-grey | #607d8b |

Income bar: Green `#2e7d32`. Net line: Dark grey `#333` dashed.

## Changes Required

1. **New DTO:** `MonthlyCashFlowDetailEntry` record in `wealthview-core`
2. **New service method:** `getMonthlyCashFlowDetail()` in `PropertyService`
3. **New controller endpoint:** `GET /properties/{id}/cashflow-detail` in `PropertyController`
4. **Tests:** Unit test for service method, controller test for endpoint
5. **Frontend API function:** `getCashFlowDetail()` in `properties.ts`
6. **Frontend type:** `MonthlyCashFlowDetailEntry` in `property.ts`
7. **New component:** `PropertyIncomeChart.tsx`
8. **Integration:** Render chart in income source cards on `IncomeSourcesPage`
