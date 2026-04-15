package com.jpmc.cibap.customer.exception;

/**
 * Thrown when a customer has no accounts matching the requested criteria.
 *
 * <p>This is a <em>business</em> exception — it represents a valid (expected) state
 * where the requested data does not exist, not a system failure. It should map to
 * HTTP 404, not 500.
 *
 * <p><strong>Behaviour with Resilience4j:</strong> This exception is <em>not</em> recorded
 * as a failure by the {@code account-db-cb} circuit-breaker. It is added to the CB's
 * {@code ignoreExceptions} list in
 * {@link com.jpmc.cibap.customer.config.Resilience4jConfig#accountDbCircuitBreaker},
 * because "no active accounts for customer X" is a valid business result, not a sign
 * of DB degradation.
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
public class AccountNotFoundException extends RuntimeException {

    /**
     * Creates an exception with the specified detail message.
     *
     * @param message human-readable description including the customer or account ID
     */
    public AccountNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and a root cause.
     *
     * @param message human-readable description
     * @param cause   the underlying exception that caused this one
     */
    public AccountNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
