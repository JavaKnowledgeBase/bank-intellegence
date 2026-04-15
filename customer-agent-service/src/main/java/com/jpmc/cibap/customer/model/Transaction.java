package com.jpmc.cibap.customer.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent entity representing a single financial transaction.
 *
 * <p>Maps to the {@code transactions} table created by Flyway migration
 * {@code V2__create_transactions.sql}. The table is <em>range-partitioned</em> by
 * {@code created_at} (monthly partitions), so queries <strong>must</strong> include a
 * date-range predicate to avoid full partition scans.
 *
 * <p><strong>Allowed status values:</strong> {@code PENDING}, {@code CLEARED}, {@code BLOCKED}
 *
 * <p><strong>Fraud scoring:</strong> {@code fraudScore} is populated asynchronously by the
 * {@code fraud-detection-service}. A value of {@code null} means the score has not yet been
 * computed. A score ≥ 0.80 triggers the {@code BLOCKED} status.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>Partition pruning only works when {@code created_at} is in the predicate. Missing
 *       the date range will cause a sequential scan across all partitions.</li>
 *   <li>{@code fraudScore} can be {@code null}; all callers must null-check before comparison.</li>
 *   <li>Negative {@code amount} values represent credits/refunds — treat sign carefully in
 *       aggregation logic.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Data
@Table("transactions")
public class Transaction {

    /** UUID primary key. PostgreSQL DEFAULT is {@code gen_random_uuid()}. */
    @Id
    private UUID id;

    /**
     * Reference to the owning account. This is a hard FK in PostgreSQL.
     *
     * <p><strong>⚠ Runtime risk:</strong> If the referenced account is deleted (CLOSED),
     * the FK constraint will prevent deletion unless a CASCADE rule is defined. This is
     * intentional for audit purposes but must be handled in any account-closure workflow.
     */
    @Column("account_id")
    private UUID accountId;

    /**
     * Transaction monetary amount in account currency.
     * Positive = debit; negative = credit/refund.
     */
    private BigDecimal amount;

    /** Human-readable merchant name. May be {@code null} for internal transfers. */
    private String merchant;

    /**
     * Spending category (e.g., {@code FOOD}, {@code TRAVEL}, {@code UTILITIES}).
     * Populated by ML classification in the fraud-detection pipeline; may be {@code null}.
     */
    private String category;

    /**
     * Fraud probability score in [0.00, 1.00]. Populated asynchronously.
     * {@code null} means not yet scored. ≥ 0.80 triggers {@code BLOCKED} status.
     *
     * <p><strong>⚠ Runtime risk:</strong> Never assume non-null. Always guard with
     * {@code fraudScore != null && fraudScore.compareTo(threshold) >= 0}.
     */
    @Column("fraud_score")
    private BigDecimal fraudScore;

    /**
     * Lifecycle status of this transaction.
     * Constrained by DB CHECK to: {@code PENDING}, {@code CLEARED}, {@code BLOCKED}.
     */
    private String status;

    /** Wall-clock time when the transaction record was created. Immutable after insert. */
    @Column("created_at")
    private Instant createdAt;
}
