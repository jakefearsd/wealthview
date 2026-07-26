package com.wealthview.api.exception;

import java.nio.charset.StandardCharsets;

import java.io.UncheckedIOException;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealthview.api.dto.ErrorResponse;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.exception.ServiceUnavailableException;
import com.wealthview.core.exception.TenantAccessDeniedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private SimpleMeterRegistry meterRegistry;
    private GlobalExceptionHandler handler;
    private HttpServletRequest request;
    private ListAppender<ILoggingEvent> appender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new GlobalExceptionHandler(meterRegistry);
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/test");

        appender = new ListAppender<>();
        appender.start();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.addAppender(appender);
        handlerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
    }

    @Test
    void handleNotFound_returns404AndRecordsMetric() {
        var ex = new EntityNotFoundException("Account not found");

        var response = handler.handleNotFound(ex, request);

        assertErrorEnvelope(response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found");
        assertCounter("EntityNotFoundException", "404", 1);
    }

    @Test
    void handleInvalidSession_returns401AndRecordsMetric() {
        var ex = new InvalidSessionException("Session expired");

        var response = handler.handleInvalidSession(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().error()).isEqualTo("UNAUTHORIZED");
        assertCounter("InvalidSessionException", "401", 1);
    }

    @Test
    void handleServiceUnavailable_returns503AndRecordsMetric() {
        var ex = new ServiceUnavailableException("Yahoo Finance client is not configured");

        var response = handler.handleServiceUnavailable(ex, request);

        assertErrorEnvelope(response, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Yahoo Finance client is not configured");
        assertCounter("ServiceUnavailableException", "503", 1);
    }

    @Test
    void handleDuplicate_returns409AndRecordsMetric() {
        var ex = new DuplicateEntityException("Email already exists");

        var response = handler.handleDuplicate(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
        assertCounter("DuplicateEntityException", "409", 1);
    }

    @Test
    void handleInvalidInvite_returns400AndRecordsMetric() {
        var ex = new InvalidInviteCodeException("Code expired");

        var response = handler.handleInvalidInvite(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("BAD_REQUEST");
        assertCounter("InvalidInviteCodeException", "400", 1);
    }

    @Test
    void handleBadCredentials_returns401AndRecordsMetric() {
        var ex = new BadCredentialsException("Wrong password");

        var response = handler.handleBadCredentials(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().status()).isEqualTo(401);
        assertCounter("BadCredentialsException", "401", 1);
    }

    @Test
    void handleAccessDenied_returns403AndRecordsMetric() {
        var ex = new AccessDeniedException("Forbidden");

        var response = handler.handleAccessDenied(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().message()).isEqualTo("Access denied");
        assertCounter("AccessDeniedException", "403", 1);
    }

    @Test
    void handleTenantAccessDenied_returns403AndRecordsMetric() {
        var ex = new TenantAccessDeniedException("Wrong tenant");

        var response = handler.handleTenantAccessDenied(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().message()).isEqualTo("Wrong tenant");
        assertCounter("TenantAccessDeniedException", "403", 1);
    }

    @Test
    void handleIllegalState_returns409AndRecordsMetric() {
        var ex = new IllegalStateException("Invalid state transition");

        var response = handler.handleIllegalState(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().status()).isEqualTo(409);
        assertCounter("IllegalStateException", "409", 1);
    }

    @Test
    void handleIllegalArgument_returns400AndRecordsMetric() {
        var ex = new IllegalArgumentException("Bad param");

        var response = handler.handleIllegalArgument(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
        assertCounter("IllegalArgumentException", "400", 1);
    }

    @Test
    void handleValidation_returns400WithFieldErrors() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must not be blank"));
        var ex = new MethodArgumentNotValidException(null, bindingResult);

        var response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("email: must not be blank");
        assertCounter("MethodArgumentNotValidException", "400", 1);
    }

    @Test
    void handleUnreadable_returns400WithCauseMessage() {
        var cause = new RuntimeException("Unexpected token");
        var inputMessage = new MockHttpInputMessage("bad".getBytes(StandardCharsets.UTF_8));
        var ex = new HttpMessageNotReadableException("Could not read", cause, inputMessage);

        var response = handler.handleUnreadable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Unexpected token");
        assertCounter("HttpMessageNotReadableException", "400", 1);
    }

    @Test
    void handleMaxUpload_returns413() {
        var ex = new MaxUploadSizeExceededException(10485760);
        var response = handler.handleMaxUpload(ex, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().status()).isEqualTo(413);
        assertCounter("MaxUploadSizeExceededException", "413", 1);
    }

    @Test
    void handleNoResourceFound_returns404AndRecordsMetric() {
        // A request to a path with no mapping (e.g. a mistyped API URL) falls through to the
        // static-resource handler and must surface as a clean 404, not the 500 catch-all.
        var ex = new NoResourceFoundException(HttpMethod.GET, "api/v1/scenarios", null);

        var response = handler.handleNoResourceFound(ex, request);

        assertErrorEnvelope(response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
        assertCounter("NoResourceFoundException", "404", 1);
    }

    @Test
    void handleGeneral_returns500AndRecordsMetric() {
        var ex = new RuntimeException("Something broke");

        var response = handler.handleGeneral(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        // RuntimeException is intentionally outside the closed whitelist; it
        // gets bucketed as "other" so the wealthview_errors_total cardinality
        // stays bounded even when a brand-new exception type lands here.
        assertCounter("other", "500", 1);
    }

    @Test
    void recordError_unknownException_isBucketedAsOther() {
        class MysteryException extends RuntimeException {
            MysteryException(String m) { super(m); }
        }
        handler.handleGeneral(new MysteryException("strange"), request);

        assertCounter("other", "500", 1);
        // And the actual class name is NOT used as a tag — that would let an
        // attacker (or a careless dev) explode cardinality by naming exception
        // classes after request data.
        assertThat(meterRegistry.find("wealthview.errors").tag("exception", "MysteryException").counter())
                .isNull();
    }

    @Test
    void recordError_incrementsCounterMultipleTimes() {
        handler.handleNotFound(new EntityNotFoundException("a"), request);
        handler.handleNotFound(new EntityNotFoundException("b"), request);
        handler.handleNotFound(new EntityNotFoundException("c"), request);

        assertCounter("EntityNotFoundException", "404", 3);
    }

    @Test
    void handleNotFound_stripsCrlfFromLoggedExceptionMessage() {
        // Entity-not-found messages often interpolate user-controlled strings
        // (account names, symbols, filenames). A malicious name containing CRLF
        // would inject a forged log line if we logged the message as-is.
        var ex = new EntityNotFoundException("Symbol not found: AAPL\r\n2026-04-22 IMPERSONATED LOG");

        handler.handleNotFound(ex, request);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .doesNotContain("\r")
                .doesNotContain("\n");
    }

    @Test
    void handleValidation_stripsCrlfFromFieldName() {
        // Field names are derived from the @Valid target — usually constant,
        // but a nested object with a user-controlled key could surface CRLF.
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email\r\nINJECTED", "must not be blank"));
        var ex = new MethodArgumentNotValidException(null, bindingResult);

        handler.handleValidation(ex, request);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .doesNotContain("\r")
                .doesNotContain("\n");
    }

    @Test
    void handleNotFound_stripsCrlfFromRequestUri() {
        // The URI can contain path segments supplied by the client.
        when(request.getRequestURI()).thenReturn("/api/v1/accounts/\r\n2026-04-22 FAKE");
        var ex = new EntityNotFoundException("nope");

        handler.handleNotFound(ex, request);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .doesNotContain("\r")
                .doesNotContain("\n");
    }

    @Test
    void handleTypeMismatch_requiredTypeNotNull_returns400WithTypeName() {
        var ex = org.mockito.Mockito.mock(MethodArgumentTypeMismatchException.class);
        org.mockito.Mockito.when(ex.getName()).thenReturn("size");
        org.mockito.Mockito.when(ex.getRequiredType()).thenReturn((Class) Integer.class);

        var response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("'size'").contains("Integer");
        assertCounter("MethodArgumentTypeMismatchException", "400", 1);
    }

    @Test
    void handleTypeMismatch_requiredTypeNull_usesGenericValueLabel() {
        // Covers the `requiredType != null ? ... : "value"` null branch
        var ex = org.mockito.Mockito.mock(MethodArgumentTypeMismatchException.class);
        org.mockito.Mockito.when(ex.getName()).thenReturn("accountId");
        org.mockito.Mockito.when(ex.getRequiredType()).thenReturn(null);

        var response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("'accountId'").contains("value");
    }

    @Test
    void handleValidation_noFieldErrors_usesOrElseFallback() {
        // Covers the `.orElse("Validation failed")` branch when stream is empty
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        // no field errors added — the stream is empty, orElse("Validation failed") fires
        var ex = new MethodArgumentNotValidException(null, bindingResult);

        var response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
    }

    @Test
    void handleUncheckedIo_withMessage_returns400WithMessage() {
        var io = new java.io.IOException("Disk full");
        var ex = new UncheckedIOException(io);

        var response = handler.handleUncheckedIo(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Disk full");
        assertCounter("UncheckedIOException", "400", 1);
    }

    @Test
    void handleUncheckedIo_withNullMessage_usesDefaultIoError() {
        // Covers the `ex.getMessage() != null ? ex.getMessage() : "I/O error"` null branch
        var io = new java.io.IOException();
        var ex = new UncheckedIOException(io) {
            @Override
            public String getMessage() { return null; }
        };

        var response = handler.handleUncheckedIo(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("I/O error");
    }

    @Test
    void handleDataIntegrity_returns400() {
        var cause = new org.hibernate.exception.ConstraintViolationException(
                "duplicate key", new java.sql.SQLException("detail"), "uq_accounts_name");
        var ex = new org.springframework.dao.DataIntegrityViolationException("conflict", cause);

        var response = handler.handleDataIntegrity(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("database constraint");
        assertCounter("DataIntegrityViolationException", "400", 1);
    }

    @Test
    void handleDateTimeParse_returns400() {
        var ex = new java.time.format.DateTimeParseException("Unparseable", "2024-99", 5);

        var response = handler.handleDateTimeParse(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("2024-99");
        assertCounter("DateTimeParseException", "400", 1);
    }

    private void assertErrorEnvelope(
            ResponseEntity<ErrorResponse> response, HttpStatus status, String error, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().status()).isEqualTo(status.value());
        assertThat(response.getBody().error()).isEqualTo(error);
        assertThat(response.getBody().message()).isEqualTo(message);
    }

    private void assertCounter(String exception, String status, double expected) {
        var counter = meterRegistry.find("wealthview.errors")
                .tag("exception", exception)
                .tag("status", status)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(expected);
    }
}
