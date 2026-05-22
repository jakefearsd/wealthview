import type { SeedManifest, TenantManifest } from './config';
import { MANIFEST_PATH } from './config';

// Loaded once at init (k6 reads the file from the mounted results volume).
const raw = open(MANIFEST_PATH);
const manifest: SeedManifest = JSON.parse(raw);

export function allTenants(): TenantManifest[] {
    return manifest.tenants;
}

// Pick a tenant for this VU, spreading VUs across tenants round-robin.
export function tenantForVu(vuId: number): TenantManifest {
    const t = manifest.tenants;
    return t[(vuId - 1) % t.length];
}
