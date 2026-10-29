package com.wealthview.projection.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

/**
 * Compares a result against its golden file. The files under {@code src/test/resources/golden} are
 * (re)written only when the {@code update.golden} system property is set; a missing golden otherwise
 * FAILS, so a deleted or misnamed file can never pass silently.
 */
public final class GoldenFiles {

    private static final String GOLDEN_DIR = "src/test/resources/golden/";

    private GoldenFiles() {
    }

    /** Asserts {@code actualJson} equals the golden {@code fileName} (strictly), honouring {@code -Dupdate.golden}. */
    public static void assertMatches(String fileName, String actualJson) throws IOException, JSONException {
        assertMatches(Path.of(GOLDEN_DIR + fileName), actualJson, Boolean.getBoolean("update.golden"));
    }

    static void assertMatches(Path golden, String actualJson, boolean update) throws IOException, JSONException {
        if (update) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actualJson);
            return;
        }
        if (!Files.exists(golden)) {
            throw new AssertionError("Golden file missing: " + golden
                    + " -- run with -Dupdate.golden=true to create it");
        }
        JSONAssert.assertEquals(Files.readString(golden), actualJson, JSONCompareMode.STRICT);
    }
}
