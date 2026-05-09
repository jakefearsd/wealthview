package com.wealthview.core.mobile.dto;

/**
 * Response shape for {@code GET /api/v1/app/version-check} and the admin
 * list/update endpoints. Snake-case JSON serialization is provided by the
 * global Jackson naming strategy.
 *
 * <p>{@code currentVersion} is null on admin list responses (there is no
 * "current" client) and populated on the public version-check.
 */
public record VersionCheckResponse(
        String platform,
        String currentVersion,
        String minimumSupportedVersion,
        String latestVersion,
        boolean updateRequired,
        boolean updateRecommended,
        String storeUrl,
        String message) {
}
