package com.wealthview.core.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonPasswordCheckerTest {

    private final CommonPasswordChecker checker = new CommonPasswordChecker();

    @Test
    void isCommon_knownCommonPassword_returnsTrue() {
        assertThat(checker.isCommon("password")).isTrue();
        assertThat(checker.isCommon("12345678")).isTrue();
        assertThat(checker.isCommon("qwerty12")).isTrue();
    }

    @Test
    void isCommon_caseInsensitive() {
        assertThat(checker.isCommon("PASSWORD")).isTrue();
        assertThat(checker.isCommon("Password")).isTrue();
        assertThat(checker.isCommon("PASSWORD123")).isTrue();
    }

    @Test
    void isCommon_uniquePassword_returnsFalse() {
        assertThat(checker.isCommon("myuniquephrase")).isFalse();
        assertThat(checker.isCommon("correct horse battery")).isFalse();
        assertThat(checker.isCommon("xK9#mP2$vL5@")).isFalse();
    }
}
