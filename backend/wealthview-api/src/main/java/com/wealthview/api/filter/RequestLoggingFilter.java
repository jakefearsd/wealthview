package com.wealthview.api.filter;

import com.wealthview.api.logging.LogSanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - startTime;
        log.info("{} {} → {} ({}ms) [tenant={} user={}]",
                LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()),
                response.getStatus(), duration,
                LogSanitizer.sanitize(MDC.get("tenantId")),
                LogSanitizer.sanitize(MDC.get("userId")));
    }
}
