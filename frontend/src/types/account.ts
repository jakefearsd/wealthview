// Wire-format Account type is owned by @wealthview/shared (used by both web
// and mobile); this module re-exports it under the existing local name to
// keep existing import paths and identifiers stable. AccountRequest is a
// frontend-only write-model type (no mobile consumer/shared equivalent) and
// stays defined here.
export type { AccountResponse as Account } from '@wealthview/shared';

export interface AccountRequest {
    name: string;
    type: string;
    institution?: string;
    currency?: string;
}
