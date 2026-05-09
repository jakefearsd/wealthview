package com.wealthview.core.mobile;

import java.util.regex.Pattern;

/**
 * Minimal semver helper for the mobile force-update flow.
 *
 * <p>This is intentionally NOT a full semver 2.0.0 implementation. We only
 * need numeric major.minor.patch ordering for the "is the installed app old
 * enough to refuse?" decision. Pre-release suffixes are recognized and
 * accepted but treated as < the same release version (so {@code 1.2.3-alpha}
 * never satisfies a minimum-version floor of {@code 1.2.3}). Pre-release
 * ordering against another pre-release is not meaningful for force-update
 * use, so two pre-release strings on the same numeric triple compare equal.
 */
final class Semver {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9.]+))?$");

    private Semver() {
    }

    static int compare(String a, String b) {
        var left = parse(a);
        var right = parse(b);

        int cmp = Integer.compare(left.major, right.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(left.minor, right.minor);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(left.patch, right.patch);
        if (cmp != 0) {
            return cmp;
        }
        // Numeric triple is equal. Pre-release < release; release > pre-release;
        // two pre-releases on the same triple are treated as equal.
        if (left.preRelease == null && right.preRelease == null) {
            return 0;
        }
        if (left.preRelease == null) {
            return 1;
        }
        if (right.preRelease == null) {
            return -1;
        }
        return 0;
    }

    static boolean isValid(String version) {
        if (version == null) {
            return false;
        }
        return PATTERN.matcher(version).matches();
    }

    private static Parts parse(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Version must not be null");
        }
        var matcher = PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semver: " + version);
        }
        return new Parts(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    private record Parts(int major, int minor, int patch, String preRelease) {
    }
}
