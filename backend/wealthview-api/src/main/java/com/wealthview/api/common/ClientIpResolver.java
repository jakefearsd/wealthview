package com.wealthview.api.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${app.rate-limit.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxies = Set.copyOf(trustedProxies);
    }

    public String resolve(HttpServletRequest request) {
        var remoteAddr = request.getRemoteAddr();
        if (!trustedProxies.contains(remoteAddr)) {
            return remoteAddr;
        }
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isEmpty()) {
            return remoteAddr;
        }
        return forwarded.split(",")[0].trim();
    }
}
