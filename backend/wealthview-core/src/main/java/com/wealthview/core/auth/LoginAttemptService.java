package com.wealthview.core.auth;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000; // 15 minutes

    private final ConcurrentMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String email) {
        var window = attempts.get(email.toLowerCase());
        if (window == null) {
            return false;
        }
        if (System.currentTimeMillis() - window.startTime > WINDOW_MS) {
            attempts.remove(email.toLowerCase());
            return false;
        }
        return window.count.get() >= MAX_ATTEMPTS;
    }

    public void recordFailure(String email) {
        attempts.compute(email.toLowerCase(), (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.startTime > WINDOW_MS) {
                return new AttemptWindow(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

    public void recordSuccess(String email) {
        attempts.remove(email.toLowerCase());
    }

    private record AttemptWindow(long startTime, AtomicInteger count) {}
}
