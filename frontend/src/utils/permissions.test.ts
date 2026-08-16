import { describe, it, expect } from 'vitest';
import { hasWriteAccess, hasAdminAccess } from './permissions';

/**
 * These predicates exist to mirror SecurityConfig exactly. When they drift, the UI either hides
 * controls a user is authorised to use (the super_admin bug these tests were written for) or shows
 * controls the server answers with a 403.
 */
describe('hasWriteAccess', () => {
    // POST/PUT/DELETE /api/v1/** -> hasAnyRole("ADMIN", "MEMBER", "SUPER_ADMIN")
    it.each(['admin', 'member', 'super_admin'])('allows %s, matching the server write roles', (role) => {
        expect(hasWriteAccess(role)).toBe(true);
    });

    it.each(['viewer', '', 'ADMIN', 'superadmin'])('denies %s', (role) => {
        expect(hasWriteAccess(role)).toBe(false);
    });

    it('denies an unauthenticated caller whose role is still null', () => {
        expect(hasWriteAccess(null)).toBe(false);
        expect(hasWriteAccess(undefined)).toBe(false);
    });
});

describe('hasAdminAccess', () => {
    // e.g. POST /api/v1/prices -> hasAnyRole("ADMIN", "SUPER_ADMIN"); members are excluded.
    it.each(['admin', 'super_admin'])('allows %s', (role) => {
        expect(hasAdminAccess(role)).toBe(true);
    });

    it.each(['member', 'viewer'])('denies %s', (role) => {
        expect(hasAdminAccess(role)).toBe(false);
    });

    it('denies a null role', () => {
        expect(hasAdminAccess(null)).toBe(false);
    });
});
