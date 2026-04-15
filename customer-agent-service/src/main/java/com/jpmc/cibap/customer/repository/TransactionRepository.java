package com.jpmc.cibap.customer.repository;

import com.jpmc.cibap.customer.model.Transaction;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link Transaction} entities.
 *
 * <p>The underlying {@code transactions} table is <em>range-partitioned</em> by
 * {@code created_at}. PostgreSQL's partition pruning only activates when the query
 * includes an equality or range predicate on the partition key. All query methods in
 * this interface explicitly include {@code created_at} bounds to guarantee pruning.
 *
 * <p><strong>Hard query limit:</strong> All date-range queries cap results at
 * {@code LIMIT 100} to protect downstream memory. For pagination beyond 100 rows,
 * a cursor-based approach should be introduced.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>Passing a {@code from} instant after {@code to} will return an empty {@link Flux}
 *       without error — always validate date ordering at the controller layer.</li>
 *   <li>Querying without the {@code created_at} predicate performs a full scan across
 *       all partitions, which is extremely expensive. Never add un-partitioned queries here.</li>
 *   <li>For very high-velocity accounts the 100-row hard limit may silently truncate
 *       results. Callers should be aware of this and not assume completeness.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
public interface TransactionRepository extends R2dbcRepository<Transaction, UUID> {

    /**
     * Fetches up to 100 transactions for a single account within a given time window,
     * ordered newest-first.
     *
     * <p>The {@code created_at} range predicate is mandatory to trigger partition pruning
     * in PostgreSQL. Without it, the query would scan all monthly partitions.
     *
     * @param accountId the UUID of the account to query
     * @param from      inclusive lower bound (must not be {@code null})
     * @param to        inclusive upper bound (must not be {@code null}); must be after {@code from}
     * @return a {@link Flux} emitting up to 100 transactions, newest-first
     */
    @Query("""
            SELECT * FROM transactions
            WHERE account_id = :accountId
              AND created_at >= :from
              AND created_at <= :to
            ORDER BY created_at DESC
            LIMIT 100
            """)
    Flux<Transaction> findByAccountIdAndDateRange(UUID accountId, Instant from, Instant to);

    /**
     * Fetches high-risk (potentially fraudulent) transactions for an account above a
     * given fraud-score threshold, within a date range.
     *
     * <p>Used by the fraud-monitoring dashboard and internal compliance reports.
     * Only rows where {@code fraud_score IS NOT NULL} are returned.
     *
     * @param accountId       the UUID of the account
     * @param minFraudScore   minimum fraud score (inclusive), in range [0.00, 1.00]
     * @param from            inclusive start of the date range
     * @param to              inclusive end of the date range
     * @return a {@link Flux} emitting matching high-risk transactions
     */
    @Query("""
            SELECT * FROM transactions
            WHERE account_id = :accountId
              AND fraud_score >= :minFraudScore
              AND fraud_score IS NOT NULL
              AND created_at >= :from
              AND created_at <= :to
            ORDER BY fraud_score DESC
            LIMIT 100
            """)
    Flux<Transaction> findHighRiskByAccountIdAndDateRange(
            UUID accountId,
            BigDecimal minFraudScore,
            Instant from,
            Instant to);
}
