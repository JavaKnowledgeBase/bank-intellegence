package com.jpmc.cibap.customer.exception;

/**
 * Thrown when a downstream system (PostgreSQL, Redis, or Kafka) is unavailable and
 * the request cannot be fulfilled, typically after circuit-breaker or retry exhaustion.
 *
 * <p>This exception maps to HTTP 503 Service Unavailable. It signals a <em>transient</em>
 * infrastructure failure — the client should retry the request after a short delay,
 * ideally using exponential back-off.
 *
 * <p><strong>When this is thrown:</strong>
 * <ul>
 *   <li>{@link com.jpmc.cibap.customer.service.AccountService} — when the
 *       {@code account-db-cb} circuit-breaker is OPEN</li>
 *   <li>{@link com.jpmc.cibap.customer.kafka.SupportRequestProducer} — when all 3
 *       Kafka publish retry attempts have been exhausted</li>
 *   <li>{@link com.jpmc.cibap.customer.controller.AccountController} — when the
 *       API rate-limiter rejects a request (mapped via {@code onErrorMap})</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
public class ServiceUnavailableException extends RuntimeException {

    /**
     * Creates an exception with the specified detail message.
     *
     * @param message a human-readable description explaining why the service is unavailable
     */
    public ServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and the original cause.
     *
     * @param message human-readable description
     * @param cause   the underlying exception (e.g., {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException})
     */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
