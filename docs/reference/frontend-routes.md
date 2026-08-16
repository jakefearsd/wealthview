[← Back to README](../../README.md)

# Frontend Pages

WealthView's frontend is a React 19 SPA built with TypeScript and Vite. The route table lives in `frontend/src/App.tsx` and uses react-router v8 — imported from **`react-router`**, not `react-router-dom`. Below is the route table with descriptions of each page.

## Route Table

| Route                    | Page Component          | Access    | Description                                                    |
|--------------------------|-------------------------|-----------|----------------------------------------------------------------|
| `/login`                 | `LoginPage`             | Public    | Email/password authentication                                  |
| `/register`              | `RegisterPage`          | Public    | New user registration with invite code                         |
| `/`                      | `DashboardPage`         | Protected | Net worth summary cards, allocation pie chart, account balances, combined portfolio history, snapshot projection, recent stock splits |
| `/accounts`              | `AccountsListPage`      | Protected | All investment accounts with balances                          |
| `/accounts/:id`          | `AccountDetailPage`     | Protected | Holdings, transactions, theoretical portfolio history chart    |
| `/accounts/:id/import`   | `ImportPage`            | Protected | CSV/OFX file upload with format selection, plus import history |
| `/holdings/:id`          | `HoldingDetailPage`     | Protected | Holding summary and the transactions behind it                 |
| `/prices`                | `PricesPage`            | Protected | Latest prices, manual price entry, recently added prices       |
| `/projections`           | `ProjectionsPage`       | Protected | Scenario card grid with create form, strategy selector         |
| `/projections/compare`   | `ProjectionComparePage` | Protected | Compare 2–3 scenarios (backend enforces `@Size(min = 2, max = 3)`) with a balance-over-time overlay and summary table |
| `/projections/:id`       | `ProjectionDetailPage`  | Protected | Config summary, edit mode, run projection, tabbed results (Balance Over Time, Annual Flows, Data Table, plus Spending Analysis / Income & Tax / Income Streams / Tax Shield when the run produces that data) |
| `/projections/:id/optimize` | `SpendingOptimizerPage` | Protected | Monte Carlo spending optimizer — optimization parameters, phase editor, Roth conversion strategy, results view |
| `/spending-profiles`     | `SpendingProfilesPage`  | Protected | Spending profiles with spending tiers, plus Monte Carlo guardrail profiles |
| `/income-sources`        | `IncomeSourcesPage`     | Protected | Create and manage reusable income sources with tax treatments  |
| `/properties`            | `PropertiesListPage`    | Protected | Rental properties overview                                     |
| `/properties/:id`        | `PropertyDetailPage`    | Protected | Income/expenses, monthly cash flow chart, valuations, investment analytics, hold-vs-sell ROI analysis |
| `/admin`                 | `AdminAreaPage`         | Protected (admin nav link) | Sectioned admin area — see [Admin Sections](#admin-sections) |
| `/export`                | `DataExportPage`        | Protected | Full JSON export and per-entity CSV downloads                  |
| `*`                      | `NotFoundPage`          | Protected | 404 page rendered inside the app shell                         |

## Legacy Redirects

Three former top-level routes now `<Navigate replace>` into the consolidated admin area:

| Old route       | Redirects to |
|-----------------|--------------|
| `/admin/prices` | `/admin`     |
| `/audit-log`    | `/admin`     |
| `/settings`     | `/admin`     |

## Routing Mechanics

- **Auth gating.** Every route except `/login` and `/register` is nested under a single parent route (`path="/"`) whose element is `<ProtectedRoute><Layout /></ProtectedRoute>`. `ProtectedRoute` reads `useAuth()`: while `loading` it renders a placeholder, when not authenticated it redirects to `/login`, otherwise it renders its children. Because the catch-all `*` route is nested inside that parent, an unknown URL shows the 404 page only for signed-in users — everyone else lands on `/login`.
- **Lazy loading.** All authenticated pages are code-split with `React.lazy(() => import(...))` and rendered inside a single `<Suspense fallback={<LoadingState />}>`. `LoginPage` and `RegisterPage` are imported eagerly so the initial download stays small; heavy dependencies such as recharts fall into the chart-bearing pages' chunks rather than the entry bundle.
- **Providers.** `<AuthProvider>` wraps `<ProjectionCacheProvider>`, which wraps `<BrowserRouter>`. A `react-hot-toast` `<Toaster position="top-right" />` sits inside the router.
- **Contexts and hooks.** Contexts: `AuthContext`, `ProjectionCacheContext`. Shared data hooks: `useApiQuery`, `useApiMutation`, `useCrudForm`.
- **API access.** `frontend/src/api/*` modules call the backend at the relative base path `/api/v1` through a shared axios client; auth travels in HttpOnly cookies with double-submit CSRF.

## Navigation

`Layout` renders a fixed left sidebar with `NavLink`s, the signed-in user's email and role, and a logout button; the routed page renders into an `<Outlet />` wrapped in an `ErrorBoundary`. The sidebar links are:

| Label | Target | Visibility |
|-------|--------|------------|
| Dashboard | `/` | All users |
| Accounts | `/accounts` | All users |
| Projections | `/projections` | All users |
| Spending Profiles | `/spending-profiles` | All users |
| Income Sources | `/income-sources` | All users |
| Properties | `/properties` | All users |
| Prices | `/prices` | All users |
| Export | `/export` | All users |
| Admin | `/admin` | `admin` and `super_admin` only |

Routes reachable only by link or redirect — `/accounts/:id`, `/accounts/:id/import`, `/holdings/:id`, `/projections/compare`, `/projections/:id`, `/projections/:id/optimize`, `/properties/:id` — have no sidebar entry.

### Admin Sections

`/admin` is a single page with its own in-page sidebar; sections are component state, not routes, so they have no URLs of their own. Non-super-admins see only the unmarked entries and land on Users; super-admins land on Dashboard.

| Section | Component | Super-admin only |
|---------|-----------|------------------|
| Dashboard | `DashboardSection` | Yes |
| Users | `UsersSection` | No |
| Tenants | `TenantsSection` | Yes |
| Prices | `PricesSection` | No |
| Stock Splits | `StockSplitsSection` | Yes |
| Exchange Rates | `ExchangeRatesSection` | No |
| Invite Codes | `InviteCodesSection` | No |
| System Config | `SystemConfigSection` | Yes |
| Audit Log | `AuditLogSection` | No |

---

## Related Docs

- [Architecture](architecture.md) — Project structure and frontend directory layout
- [Configuration Reference](configuration.md) — Frontend build-time settings and dev-server proxy
- [Development Guide](../development.md) — Frontend build and test commands
- [Feature Walkthrough](../feature_walkthrough.md) — Step-by-step guided tour of all features
