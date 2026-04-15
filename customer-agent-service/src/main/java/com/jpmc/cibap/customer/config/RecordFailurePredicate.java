package com.jpmc.cibap.customer.config;

import com.jpmc.cibap.customer.exception.AccountNotFoundException;
import com.jpmc.cibap.customer.exception.ServiceUnavailableException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Circuit-breaker failure predicate that distinguishes infrastructure failures
 * from expected business exceptions.
 *
 * <p>Resilience4j uses this predicate (configured via
 * {@code resilience4j.circuitbreaker.configs.default.record-failure-predicate}
 * in {@code application.yml}) to decide whether a thrown exception should
 * increment the circuit-breaker's failure counter.
 *
 * <h2>Design principle</h2>
 * <p>Only <em>infrastructure</em> faults should trip the circuit breaker.
 * Business exceptions such as {@link AccountNotFoundException} indicate that
 * the downstream service responded correctly (the customer simply has no accounts),
 * so recording them as failures would cause the circuit to open spuriously and
 * degrade service for all customers when a few are not found.
 *
 * <h2>What counts as a failure (returns {@code true})</h2>
 * <ul>
 *   <li>{@link IOException} — network or Redis/Kafka connectivity errors</li>
 *   <li>{@link TimeoutException} — reactive timeout (e.g., R2DBC pool exhausted)</li>
 *   <li>Any other unexpected exception not explicitly excluded below</li>
 * </ul>
 *
 * <h2>What does NOT count as a failure (returns {@code false})</h2>
 * <ul>
 *   <li>{@link AccountNotFoundException} — valid "not found" business response</li>
 *   <li>{@link ServiceUnavailableException} — already represents a propagated 503
 *       from a downstream circuit-breaker; counting it twice would double-trip the CB</li>
 *   <li>{@link IllegalArgumentException} — bad input; a client bug, not an infra failure</li>
 * </ul>
 *
 * <p><strong>⚠ Runtime risk:</strong> If a new business exception is introduced and
 * NOT added to the exclusion list here, it will count as a circuit-breaker failure.
 * Review this predicate whenever new application exceptions are added.
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
public class RecordFailurePredicate implements Predicate<Throwable> {

    /**
     * Returns {@code true} if the given throwable represents an infrastructure
     * failure that should increment the circuit-breaker's failure counter.
     *
     * @param throwable the exception thrown by the protected call
     * @return {@code true} to record as failure; {@code false} to ignore
     */
    @Override
    public boolean test(Throwable throwable) {
        // ── Business exceptions — do NOT trip the circuit breaker ────────────
        // These indicate a valid (if unhappy) response from the downstream system.
        if (throwable instanceof AccountNotFoundException) {
            return false;
        }
        if (throwable instanceof ServiceUnavailableException) {
            // Already a propagated circuit-breaker failure — avoid double-counting
            return false;
        }
        if (throwable instanceof IllegalArgumentException) {
            // Bad client input; infrastructure is healthy
            return false;
        }

        // ── Infrastructure failures — trip the circuit breaker ───────────────
        // IOException covers Redis/Kafka/network connectivity errors.
        // TimeoutException covers R2DBC pool exhaustion and reactive timeouts.
        // All other unexpected exceptions also count as infrastructure failures.
        return true;
    }
}
