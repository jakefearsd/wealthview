import { vi } from 'vitest';

/**
 * Shared vitest automock for the API client ({@link ../client.ts}).
 *
 * Vitest's `__mocks__` convention picks this up automatically for any
 * factory-less `vi.mock('./client')` call in a sibling `*.test.ts` file — no
 * per-file `vi.hoisted` + inline factory needed. Exposes a `vi.fn()` for
 * every method the real client's callers actually invoke across `api/*.ts`
 * (get/post/put/delete). Consuming tests should reset it with
 * `beforeEach(() => vi.clearAllMocks())`.
 */
export default {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
};
