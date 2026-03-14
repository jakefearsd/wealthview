[← Back to README](../../README.md)

# Settings and Data Export

WealthView provides settings for managing notifications, users, and invite codes, as well as tools for exporting your data.

---

## Notification Preferences

Navigate to **Settings** in the sidebar to manage your notification preferences.

You can toggle notifications on or off for each notification type:

| Notification Type | What It Tells You |
|-------------------|-------------------|
| **Price Alert** | Notifies you when a security price changes significantly. |
| **Import Complete** | Notifies you when a data import finishes processing. |

Adjust these based on how closely you want to monitor activity in your account.

---

## Invite Codes

Admins can generate invite codes to allow new users to register.

### Generating a Code

1. Navigate to **Settings** in the sidebar.
2. In the **Invite Codes** section, click **Generate Code**.
3. A new code is created and displayed. Copy it and share it with the person you want to invite.

### Code Rules

- Each code is **single-use** — once someone registers with it, the code is consumed.
- Codes **expire after 7 days**. If the invitee does not register in time, generate a new code.
- The Settings page shows all active (unused, unexpired) codes and consumed codes, so you can track who has been invited.

### Who Can Generate Codes

Only users with the **Admin** or **Super-Admin** role can generate invite codes. Members and viewers do not have access to this feature.

---

## User Management

Admins can manage users within their tenant from the Settings page.

### Viewing Users

The **Users** section lists all users in your tenant, showing their name, email, and current role.

### Changing Roles

To change a user's role:

1. Find the user in the list.
2. Select a new role: **Viewer**, **Member**, or **Admin**.
3. Save the change.

Role descriptions:

| Role | Permissions |
|------|------------|
| **Viewer** | Read-only access to all data. |
| **Member** | Can create and edit accounts, transactions, properties, and projections. |
| **Admin** | All member permissions, plus user management and invite code generation. |

### Removing Users

Admins can remove a user from the tenant. Removing a user revokes their access but does **not** delete the tenant's data. All accounts, transactions, properties, and projections remain intact because data is scoped to the tenant, not to individual users.

---

## Data Export

Navigate to **Export** in the sidebar to download your data.

### Full JSON Export

Click **Export JSON** to download a single JSON file containing all of your tenant's data:

- Accounts
- Transactions
- Holdings
- Properties (with income, expenses, valuations)
- Projections (with accounts, spending profiles, income sources)

This is a comprehensive backup of everything in your WealthView tenant.

### CSV Exports

For spreadsheet-compatible exports, download individual CSV files for each entity type:

| Export | What It Contains |
|--------|-----------------|
| **Accounts CSV** | All accounts with their type, institution, and balances. |
| **Transactions CSV** | All transactions across all accounts with date, type, symbol, quantity, and amount. |
| **Holdings CSV** | Current holdings across all accounts with quantity, cost basis, and market value. |

### Use Cases

- **Backup** — Regularly export JSON to have an offline copy of all your data.
- **Spreadsheet analysis** — Import CSVs into Excel or Google Sheets for custom analysis, charting, or pivot tables.
- **Tax preparation** — Export transactions for a specific period to share with your accountant.
- **Migration** — If you need to move to a different tool, the JSON export provides a complete data set.
