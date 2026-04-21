package com.wealthview.core.auth;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000; // 15 minutes

    private final ConcurrentMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String email) {
        var key = email.toLowerCase(Locale.ROOT);
        var window = attempts.get(key);
        if (window == null) {
            return false;
        }
        if (System.currentTimeMillis() - window.startTime > WINDOW_MS) {
            attempts.remove(key);
            return false;
        }
        return window.count.get() >= MAX_ATTEMPTS;
    }

    public void recordFailure(String email) {
        attempts.compute(email.toLowerCase(Locale.ROOT), (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.startTime > WINDOW_MS) {
                return new AttemptWindow(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

    public void recordSuccess(String email) {
        attempts.remove(email.toLowerCase(Locale.ROOT));
    }

    private record AttemptWindow(long startTime, AtomicInteger count) {}
}
