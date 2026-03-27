package com.wealthview.api.exception;

import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.exception.TenantAccessDeniedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private SimpleMeterRegistry meterRegistry;
    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new GlobalExceptionHandler(meterRegistry);
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/test");
    }

    @Test
    void handleNotFound_returns404AndRecordsMetric() {
        var ex = new EntityNotFoundException("Account not found");

        var response = handler.handleNotFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Account not found");
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
    void handleGeneral_returns500AndRecordsMetric() {
        var ex = new RuntimeException("Something broke");

        var response = handler.handleGeneral(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertCounter("RuntimeException", "500", 1);
    }

    @Test
    void recordError_incrementsCounterMultipleTimes() {
        handler.handleNotFound(new EntityNotFoundException("a"), request);
        handler.handleNotFound(new EntityNotFoundException("b"), request);
        handler.handleNotFound(new EntityNotFoundException("c"), request);

        assertCounter("EntityNotFoundException", "404", 3);
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
