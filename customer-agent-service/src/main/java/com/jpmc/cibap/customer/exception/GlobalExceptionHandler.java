package com.jpmc.cibap.customer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global reactive exception handler mapping application exceptions to RFC 7807
 * {@link ProblemDetail} responses.
 *
 * <h2>Why RFC 7807 (Problem Detail)?</h2>
 * <p>Problem Detail provides a standardised, machine-readable error format. Downstream
 * services and the API gateway can parse the {@code type} URI and {@code status} code
 * without relying on free-form error message strings.
 *
 * <h2>Exception-to-HTTP mapping</h2>
 * <table border="1">
 *   <tr><th>Exception</th><th>HTTP</th><th>Notes</th></tr>
 *   <tr><td>{@link AccountNotFoundException}</td><td>404</td><td>Business: no accounts found</td></tr>
 *   <tr><td>{@link ServiceUnavailableException}</td><td>503</td><td>CB open / Kafka exhausted / rate limit</td></tr>
 *   <tr><td>{@link IllegalArgumentException}</td><td>400</td><td>Bad input, e.g. from > to</td></tr>
 *   <tr><td>{@link WebExchangeBindException}</td><td>400</td><td>Bean Validation failures (@Valid)</td></tr>
 *   <tr><td>{@link ServerWebInputException}</td><td>400</td><td>Type mismatch, missing param</td></tr>
 *   <tr><td>{@link AuthenticationException}</td><td>401</td><td>Invalid/missing JWT</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403</td><td>Insufficient scope</td></tr>
 *   <tr><td>{@link Exception}</td><td>500</td><td>Catch-all — never expose internals</td></tr>
 * </table>
 *
 * <p><strong>⚠ Security note:</strong> The catch-all {@code Exception} handler intentionally
 * does NOT include the exception message in the response body. Stack traces and internal error
 * messages are logged only — never returned to the client. Exposing internals (e.g., SQL state,
 * class names) enables information leakage attacks.
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String PROBLEM_BASE_URI = "https://api.chase.internal/problems/";

    // ─── Business exceptions ──────────────────────────────────────────────────

    /**
     * Handles {@link AccountNotFoundException} → HTTP 404.
     *
     * <p>Safe to include the message in the response — it contains only the customer ID,
     * not any sensitive account data.
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public Mono<ProblemDetail> handleAccountNotFound(AccountNotFoundException ex) {
        log.warn("[GlobalExceptionHandler] AccountNotFoundException: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account Not Found");
        problem.setType(URI.create(PROBLEM_BASE_URI + "account-not-found"));
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }

    /**
     * Handles {@link ServiceUnavailableException} → HTTP 503.
     *
     * <p>Instructs the client to retry via the {@code Retry-After} hint in the response
     * properties. The message is safe to include (it was constructed by our code).
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public Mono<ProblemDetail> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("[GlobalExceptionHandler] ServiceUnavailableException: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Service Unavailable");
        problem.setType(URI.create(PROBLEM_BASE_URI + "service-unavailable"));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("retryAfterSeconds", 5);  // Hint to clients
        return Mono.just(problem);
    }

    // ─── Input validation exceptions ─────────────────────────────────────────

    /**
     * Handles {@link IllegalArgumentException} → HTTP 400.
     * Typically thrown when the controller detects an invalid parameter combination
     * (e.g., {@code from} after {@code to}).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[GlobalExceptionHandler] IllegalArgumentException: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create(PROBLEM_BASE_URI + "bad-request"));
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }

    /**
     * Handles {@link WebExchangeBindException} → HTTP 400.
     * Fired by Spring's {@code @Valid} when Bean Validation constraints are violated.
     * Returns a map of {@code fieldName → errorMessage} to give clients actionable feedback.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ProblemDetail> handleValidationErrors(WebExchangeBindException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (a, b) -> a  // Keep first error per field
                ));

        log.warn("[GlobalExceptionHandler] Validation failed: {}", fieldErrors);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Validation Error");
        problem.setType(URI.create(PROBLEM_BASE_URI + "validation-error"));
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }

    /**
     * Handles {@link ServerWebInputException} → HTTP 400.
     * Covers type-conversion failures (e.g., malformed UUID or invalid Instant format).
     */
    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ProblemDetail> handleInputError(ServerWebInputException ex) {
        log.warn("[GlobalExceptionHandler] ServerWebInputException: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid request parameter: " + ex.getReason());
        problem.setTitle("Bad Request");
        problem.setType(URI.create(PROBLEM_BASE_URI + "bad-request"));
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }

    // ─── Security exceptions ──────────────────────────────────────────────────

    /**
     * Handles {@link AuthenticationException} → HTTP 401.
     *
     * <p><strong>⚠ Security note:</strong> Do not include the exception message — it may
     * contain token details (e.g., "JWT expired at ...") that help an attacker understand
     * the token's lifetime.
     */
    @ExceptionHandler(AuthenticationException.class)
    public Mono<ProblemDetail> handleUnauthorized(AuthenticationException ex) {
        log.warn("[GlobalExceptionHandler] AuthenticationException — rejecting request");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication required");
        problem.setTitle("Unauthorized");
        problem.setType(URI.create(PROBLEM_BASE_URI + "unauthorized"));
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }

    /**
     * Handles {@link AccessDeniedException} → HTTP 403.
     * Thrown when the JWT is valid but lacks the required OAuth2 scope.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ProblemDetail> handleForbidden(AccessDeniedException ex) {
        log.warn("[GlobalExceptionHandler] AccessDeniedException — insufficient scope");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
        problem.setTitle("Forbidden");
        problem.setType(URI.create(PROBLEM_BASE_URI + "forbidden"));
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }

    // ─── Catch-all ────────────────────────────────────────────────────────────

    /**
     * Catch-all handler for unexpected exceptions → HTTP 500.
     *
     * <p><strong>⚠ Security note:</strong> The exception message and stack trace are
     * logged but NEVER included in the response. Internal details (SQL state, class
     * names, file paths) must never reach the client.
     */
    @ExceptionHandler(Exception.class)
    public Mono<ProblemDetail> handleGeneric(Exception ex) {
        // Log at ERROR with full stack trace for SRE investigation
        log.error("[GlobalExceptionHandler] Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support if the problem persists.");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create(PROBLEM_BASE_URI + "internal-error"));
        problem.setProperty("timestamp", Instant.now().toString());
        return Mono.just(problem);
    }
}
