// Re-export from @wealthview/shared keeps the existing import path stable
// while migrating this utility to the shared workspace package — it's also
// consumed directly by mobile's AuthContext. New code may import directly
// from '@wealthview/shared'.
export { extractErrorMessage } from '@wealthview/shared';
