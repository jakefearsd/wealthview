[← Back to README](../../README.md)

# Frontend Pages

WealthView's frontend is a React 18 SPA built with TypeScript and Vite. Below is the route table with descriptions of each page.

## Route Table

| Route                    | Page                    | Description                                                    |
|--------------------------|-------------------------|----------------------------------------------------------------|
| `/`                      | Dashboard               | Net worth, allocation pie chart, account balances              |
| `/accounts`              | Accounts List           | All investment accounts with balances                          |
| `/accounts/:id`          | Account Detail          | Holdings, transactions, theoretical portfolio history chart    |
| `/accounts/:id/import`   | Import                  | CSV/OFX file upload with format selection                      |
| `/holdings/:id`          | Holding Detail          | Individual holding details                                     |
| `/prices`                | Prices                  | Stock price lookup and history                                 |
| `/projections`           | Projections List        | Scenario card grid with create form, strategy selector         |
| `/projections/compare`   | Scenario Comparison     | Compare up to 3 scenarios with overlay chart and summary table |
| `/projections/:id`       | Projection Detail       | Config summary, edit mode, run projection, tabbed results with spending analysis |
| `/spending-profiles`     | Spending Profiles       | Create and manage spending profiles with spending tiers        |
| `/income-sources`        | Income Sources          | Create and manage reusable income sources with tax treatments  |
| `/properties`            | Properties List         | Rental properties overview                                     |
| `/properties/:id`        | Property Detail         | Income/expenses, monthly cash flow chart, investment analytics panel with metric explanations |
| `/admin`                 | Admin Panel             | Super-admin tenant management (create, list, enable/disable)   |
| `/audit-log`             | Audit Log               | Paginated, filterable audit event history                      |
| `/export`                | Data Export             | Full JSON export and per-entity CSV downloads                  |
| `/settings`              | Settings                | Notification preferences, invite codes, user management (admin) |
| `/login`                 | Login                   | Email/password authentication                                  |
| `/register`              | Register                | New user registration with invite code                         |

---

## Related Docs

- [Architecture](architecture.md) — Project structure and frontend directory layout
- [Development Guide](../development.md) — Frontend build and test commands
- [Feature Walkthrough](../feature_walkthrough.md) — Step-by-step guided tour of all features
