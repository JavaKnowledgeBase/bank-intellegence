package com.jpmc.cibap.customer.repository;

import com.jpmc.cibap.customer.model.Account;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link Account} entities.
 *
 * <p>Spring Data R2DBC generates implementations at startup. All methods return cold
 * reactive publishers — nothing executes until a subscriber calls {@code subscribe()}.
 *
 * <p><strong>Connection-pool note:</strong> Each subscription acquires a connection from
 * the R2DBC pool (initial: 5, max: 20). Under heavy load, subscriptions may queue
 * waiting for a free connection. Monitor {@code r2dbc.pool.acquired} metric in Prometheus.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>{@link org.springframework.dao.DataAccessException} will propagate as a reactive
 *       error signal for any SQL or connectivity failure.</li>
 *   <li>Custom {@code @Query} methods bypass Spring Data's type mapping — ensure
 *       result columns exactly match entity field names (snake_case ↔ camelCase via
 *       R2DBC naming strategy).</li>
 *   <li>{@code findByCustomerId} returns an empty {@link Flux} (not an error) when no
 *       accounts exist — callers must handle the empty case explicitly.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
public interface AccountRepository extends R2dbcRepository<Account, UUID> {

    /**
     * Fetches <em>all</em> accounts (any status) belonging to the given customer.
     *
     * <p>Used by the transaction-history pipeline to resolve all account IDs before
     * querying the transaction table.
     *
     * @param customerId the customer's UUID; must not be {@code null}
     * @return a {@link Flux} emitting zero or more accounts
     */
    Flux<Account> findByCustomerId(UUID customerId);

    /**
     * Fetches accounts matching a specific lifecycle status for a given customer.
     *
     * <p>Primarily used to build the account summary (only {@code ACTIVE} accounts are
     * included in the public API response).
     *
     * @param customerId the customer's UUID; must not be {@code null}
     * @param status     the target status ({@code ACTIVE}, {@code FROZEN}, or {@code CLOSED})
     * @return a {@link Flux} emitting zero or more matching accounts
     */
    Flux<Account> findByCustomerIdAndStatus(UUID customerId, String status);

    /**
     * Checks whether a customer has at least one account in the given status.
     *
     * <p>Used by the support-request service to verify the customer exists before
     * accepting a support request.
     *
     * @param customerId the customer's UUID
     * @param status     lifecycle status to check for
     * @return a {@link Mono} emitting {@code true} if at least one match exists
     */
    @Query("SELECT COUNT(*) > 0 FROM accounts WHERE customer_id = :customerId AND status = :status")
    Mono<Boolean> existsByCustomerIdAndStatus(UUID customerId, String status);
}
