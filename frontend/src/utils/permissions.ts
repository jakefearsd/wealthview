/**
 * Role predicates, mirroring the server's authorisation rules in
 * `wealthview-api/.../security/SecurityConfig.java`.
 *
 * The role string is whatever `useAuth().role` holds — the value the server put in the token
 * (`TokenService` emits `super_admin` for a super admin, otherwise the stored role), so `null`
 * simply means "not authenticated yet".
 *
 * These live in one place because the same predicate was previously restated on every page, and
 * one copy drifted: four pages omitted `super_admin` and hid write controls from users the server
 * happily accepts. Gate UI on these helpers rather than comparing role strings inline.
 */

/** Roles the server accepts on POST / PUT / DELETE `/api/v1/**`. */
const WRITE_ROLES: ReadonlySet<string> = new Set(['admin', 'member', 'super_admin']);

/** Roles the server accepts on the admin-only endpoints (prices, invite codes, tenant users). */
const ADMIN_ROLES: ReadonlySet<string> = new Set(['admin', 'super_admin']);

/** True when the role may create, update or delete ordinary tenant data. */
export function hasWriteAccess(role: string | null | undefined): boolean {
    return role != null && WRITE_ROLES.has(role);
}

/** True when the role has tenant-administration rights — a strict subset of {@link hasWriteAccess}. */
export function hasAdminAccess(role: string | null | undefined): boolean {
    return role != null && ADMIN_ROLES.has(role);
}
