package com.wealthview.api.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private ListAppender<ILoggingEvent> appender;
    private Logger filterLogger;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        filterLogger.addAppender(appender);
        filterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
    }

    @Test
    void doFilterInternal_passesRequestThrough() throws ServletException, IOException {
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_preservesResponseStatus() throws ServletException, IOException {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doFilterInternal_stripsCrlfFromLoggedUri() throws ServletException, IOException {
        // An attacker who controls part of the URI (e.g. via path traversal or
        // a crafted header-derived value) could inject CRLF to forge a second
        // log line. The logged URI must have CRLF stripped before emission.
        var request = new MockHttpServletRequest("GET", "/api/v1/accounts\r\n2026-04-22 FAKE LOG");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(appender.list).hasSize(1);
        var formatted = appender.list.get(0).getFormattedMessage();
        assertThat(formatted)
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThat(formatted).contains("/api/v1/accounts2026-04-22 FAKE LOG");
    }

    @Test
    void doFilterInternal_stripsCrlfFromLoggedMethod() throws ServletException, IOException {
        // Tomcat rejects malformed methods, but defense in depth — the log
        // message must still sanitize if a value containing CRLF ever reaches it.
        var request = new MockHttpServletRequest("GET\r\nX-Injected: evil", "/api/v1/ping");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .doesNotContain("\r")
                .doesNotContain("\n");
    }
}
