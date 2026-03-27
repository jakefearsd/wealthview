package com.wealthview.api.exception;

import com.wealthview.api.dto.ErrorResponse;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.exception.TenantAccessDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private void recordError(Exception ex, HttpStatus status) {
        meterRegistry.counter("wealthview.errors",
                "exception", ex.getClass().getSimpleName(),
                "status", String.valueOf(status.value())).increment();
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        log.warn("{} {} - Entity not found: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.NOT_FOUND);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage(), 404));
    }

    @ExceptionHandler(InvalidSessionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSession(InvalidSessionException ex, HttpServletRequest request) {
        log.warn("{} {} - Invalid session: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.UNAUTHORIZED);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", ex.getMessage(), 401));
    }

    @ExceptionHandler(DuplicateEntityException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateEntityException ex, HttpServletRequest request) {
        log.warn("{} {} - Duplicate entity: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.CONFLICT);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", ex.getMessage(), 409));
    }

    @ExceptionHandler(InvalidInviteCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInvite(InvalidInviteCodeException ex, HttpServletRequest request) {
        log.warn("{} {} - Invalid invite code: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage(), 400));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        log.warn("{} {} - Bad credentials: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.UNAUTHORIZED);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", ex.getMessage(), 401));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("{} {} - Access denied: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.FORBIDDEN);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "Access denied", 403));
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleTenantAccessDenied(TenantAccessDeniedException ex, HttpServletRequest request) {
        log.warn("{} {} - Tenant access denied: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.FORBIDDEN);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", ex.getMessage(), 403));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("{} {} - Illegal state: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.CONFLICT);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", ex.getMessage(), 409));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("{} {} - Illegal argument: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        recordError(ex, HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage(), 400));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        log.warn("{} {} - Validation failed: {}", request.getMethod(), request.getRequestURI(), message);
        recordError(ex, HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", message, 400));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("{} {} - Request body not readable: {}", request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        recordError(ex, HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST",
                        "Invalid request body: " + ex.getMostSpecificCause().getMessage(), 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("{} {} - Unhandled exception", request.getMethod(), request.getRequestURI(), ex);
        recordError(ex, HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred", 500));
    }
}
