// Central knobs read from k6 env (-e KEY=VAL) with sane defaults.
export const BASE_URL = __ENV.BASE_URL || 'http://loadtest-app:8080';
export const MANIFEST_PATH = __ENV.MANIFEST_PATH || '/loadtest/results/manifest.json';
export const VUS_MAX = parseInt(__ENV.VUS_MAX || '200', 10);

export interface TenantManifest {
  tenantId: string;
  email: string;
  password: string;
}
export interface SeedManifest {
  tenants: TenantManifest[];
}
