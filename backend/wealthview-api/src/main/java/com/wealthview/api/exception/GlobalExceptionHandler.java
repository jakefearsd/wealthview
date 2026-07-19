package com.wealthview.api.exception;

import java.io.UncheckedIOException;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.wealthview.api.dto.ErrorResponse;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.exception.ServiceUnavailableException;
import com.wealthview.core.exception.TenantAccessDeniedException;
import io.micrometer.core.instrument.MeterRegistry;

import static com.wealthview.api.logging.LogSanitizer.sanitize;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry meterRegistry;

    /**
     * Closed whitelist of exception class names that may appear as a meter tag,
     * derived from this class's own {@code @ExceptionHandler} declarations so it
     * can never drift when a handler is added or removed. Anything else is
     * bucketed as {@code other} so the {@code wealthview_errors_total} time
     * series stays bounded even for exception types without a dedicated handler.
     */
    private static final Set<String> KNOWN_EXCEPTIONS = Arrays.stream(GlobalExceptionHandler.class.getMethods())
            .map(method -> method.getAnnotation(ExceptionHandler.class))
            .filter(Objects::nonNull)
            .flatMap(annotation -> Arrays.stream(annotation.value()))
            .map(Class::getSimpleName)
            .collect(Collectors.toUnmodifiableSet());

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private void recordError(Exception ex, HttpStatus status) {
        var simpleName = ex.getClass().getSimpleName();
        var bucketed = KNOWN_EXCEPTIONS.contains(simpleName) ? simpleName : "other";
        meterRegistry.counter("wealthview.errors",
                "exception", bucketed,
                "status", String.valueOf(status.value())).increment();
    }

    /**
     * The shared shape of every non-5xx handler: one WARN line, one error
     * counter increment, one standard envelope. {@code logMessage} and
     * {@code bodyMessage} are separate because a few handlers deliberately log
     * more detail than they return (or return a fixed string regardless of the
     * exception text).
     */
    private ResponseEntity<ErrorResponse> respond(Exception ex, HttpServletRequest request, HttpStatus status,
                                                  String code, String logLabel, String logMessage,
                                                  String bodyMessage) {
        log.warn("{} {} - {}: {}",
                sanitize(request.getMethod()), sanitize(request.getRequestURI()), logLabel, sanitize(logMessage));
        recordError(ex, status);
        return ResponseEntity.status(status).body(new ErrorResponse(code, bodyMessage, status.value()));
    }

    private ResponseEntity<ErrorResponse> respond(Exception ex, HttpServletRequest request, HttpStatus status,
                                                  String code, String logLabel, String message) {
        return respond(ex, request, status, code, logLabel, message, message);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            EntityNotFoundException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.NOT_FOUND, "NOT_FOUND", "Entity not found", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        // A path with no controller mapping falls through to the static-resource handler; without
        // this it lands in the 500 catch-all with an ERROR stack trace. The body message is fixed
        // ("Resource not found") — the exception text talks about "static resources", which would
        // mislead API callers.
        return respond(ex, request, HttpStatus.NOT_FOUND, "NOT_FOUND", "No mapping for path",
                ex.getMessage(), "Resource not found");
    }

    @ExceptionHandler(InvalidSessionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSession(
            InvalidSessionException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid session", ex.getMessage());
    }

    @ExceptionHandler(DuplicateEntityException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(
            DuplicateEntityException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.CONFLICT, "CONFLICT", "Duplicate entity", ex.getMessage());
    }

    @ExceptionHandler(InvalidInviteCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInvite(
            InvalidInviteCodeException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid invite code", ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bad credentials", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied",
                ex.getMessage(), "Access denied");
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleTenantAccessDenied(
            TenantAccessDeniedException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.FORBIDDEN, "FORBIDDEN", "Tenant access denied", ex.getMessage());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(
            ServiceUnavailableException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Optional service not configured", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.CONFLICT, "CONFLICT", "Illegal state", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Illegal argument", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        var message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Validation failed", message);
    }

    /**
     * Triggered when a query parameter cannot be coerced into the controller
     * argument type — e.g. {@code ?size=abc} bound to {@code int size}, or
     * {@code ?id=not-a-uuid} bound to {@code UUID id}. Without this handler,
     * Spring's default behavior is to surface the conversion failure as a
     * 500 (since {@code MethodArgumentTypeMismatchException} is not a built-in
     * 4xx) — see fuzz coverage in PaginationFuzzIT and the matching commit
     * that introduced this handler.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        var requiredType = ex.getRequiredType();
        var typeName = requiredType != null ? requiredType.getSimpleName() : "value";
        var message = "Parameter '" + ex.getName() + "' must be a valid " + typeName;
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Type mismatch", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        var cause = ex.getMostSpecificCause().getMessage();
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body not readable",
                cause, "Invalid request body: " + cause);
    }

    /**
     * Surfaces {@link DateTimeParseException} as 400. Endpoints that take
     * date / YearMonth / OffsetDateTime as a query parameter parse it via
     * {@code YearMonth.parse(...)} (etc.) inside the controller method. A bad
     * input like {@code ?from=2024-00} or {@code ?from=abc} otherwise reaches
     * the catch-all 500. Surfaced by DateValidationFuzzIT.
     */
    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ErrorResponse> handleDateTimeParse(
            DateTimeParseException ex, HttpServletRequest request) {
        var message = "Invalid date or time format: " + ex.getParsedString();
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Date/time parse failure", message);
    }

    /**
     * Surfaces commons-csv / I/O parsing failures from file uploads as 400.
     * ImportBoundaryFuzzIT relies on this to keep random-byte / random-row
     * uploads in the 4xx band.
     */
    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ErrorResponse> handleUncheckedIo(
            UncheckedIOException ex, HttpServletRequest request) {
        var message = ex.getMessage() != null ? ex.getMessage() : "I/O error";
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Unchecked I/O",
                message, "Failed to parse uploaded content: " + message);
    }

    /**
     * Surfaces persistence-layer rejections (column-length, numeric-precision,
     * UTF-8-encoding, and unique-key violations) as 400 BAD_REQUEST. Surfaced
     * by NumericAndStringInjectionFuzzIT.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        var cause = ex.getMostSpecificCause().getMessage();
        return respond(ex, request, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Data integrity violation",
                cause, "Request violates a database constraint: " + cause);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return respond(ex, request, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "File too large",
                ex.getMessage(), "File size exceeds the 10MB limit");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("{} {} - Unhandled exception",
                sanitize(request.getMethod()), sanitize(request.getRequestURI()), ex);
        recordError(ex, HttpStatus.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred", 500));
    }
}
