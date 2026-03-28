package com.wealthview.core.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CommonPasswordChecker {

    private static final Set<String> COMMON_PASSWORDS = loadPasswords();

    private static Set<String> loadPasswords() {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("common-passwords.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(line -> line.trim().toLowerCase(Locale.US))
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load common passwords list", e);
        }
    }

    public boolean isCommon(String password) {
        return COMMON_PASSWORDS.contains(password.toLowerCase(Locale.US));
    }
}
