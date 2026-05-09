-- Mobile force-update / version-check support.
--
-- Single global row per platform: the backend tells the mobile client which
-- versions are still acceptable on app launch. Operators bump
-- minimum_supported_version to force a hard update banner; latest_version
-- drives the soft "update available" prompt. The optional message is shown
-- alongside the prompt ("required for new tax features").
--
-- This is NOT system_config: it is a small fixed schema with typed columns,
-- semver-shape CHECK constraints at the DB layer (defense-in-depth), and a
-- two-row enumeration today. A dedicated table is cleaner than stuffing
-- nested JSON into a key/value store.
--
-- Seed values are intentionally placeholders (0.0.1 / 0.0.1) and dummy
-- store URLs. Operators MUST update them via the admin endpoint
-- (PUT /api/v1/admin/mobile-versions/{platform}) before announcing a
-- mobile build.
CREATE TABLE IF NOT EXISTS mobile_app_versions (
    platform                  text PRIMARY KEY CHECK (platform IN ('android', 'ios')),
    minimum_supported_version text NOT NULL,
    latest_version            text NOT NULL,
    store_url                 text NOT NULL,
    message                   text NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_mobile_app_versions_min_semver
        CHECK (minimum_supported_version ~ '^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$'),
    CONSTRAINT chk_mobile_app_versions_latest_semver
        CHECK (latest_version ~ '^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$')
);

INSERT INTO mobile_app_versions (platform, minimum_supported_version, latest_version, store_url) VALUES
    ('android', '0.0.1', '0.0.1', 'https://play.google.com/store/apps/details?id=com.wealthview'),
    ('ios',     '0.0.1', '0.0.1', 'https://apps.apple.com/app/wealthview/id000000000')
ON CONFLICT (platform) DO NOTHING;
