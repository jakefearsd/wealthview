package com.wealthview.projection.testutil;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldenFilesTest {

    @TempDir
    Path dir;

    @Test
    void assertMatches_missingGoldenWithoutUpdateSwitch_failsNamingTheFile() {
        var golden = dir.resolve("absent.json");

        assertThatThrownBy(() -> GoldenFiles.assertMatches(golden, "{}", false))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("absent.json")
                .hasMessageContaining("update.golden");
        assertThat(golden).doesNotExist();
    }

    @Test
    void assertMatches_missingGoldenWithUpdateSwitch_writesTheFile() throws Exception {
        var golden = dir.resolve("sub/new.json");

        GoldenFiles.assertMatches(golden, "{\"a\":1}", true);

        assertThat(Files.readString(golden)).isEqualTo("{\"a\":1}");
    }

    @Test
    void assertMatches_differentContent_fails() throws Exception {
        var golden = dir.resolve("g.json");
        Files.writeString(golden, "{\"a\":1}");

        assertThatThrownBy(() -> GoldenFiles.assertMatches(golden, "{\"a\":2}", false))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void assertMatches_sameContent_passes() throws Exception {
        var golden = dir.resolve("g.json");
        Files.writeString(golden, "{\"a\":1}");

        GoldenFiles.assertMatches(golden, "{ \"a\" : 1 }", false);
    }
}
