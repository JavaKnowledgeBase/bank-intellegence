package com.jpmc.cibap.customer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only DTO (Data Transfer Object) that aggregates a customer's account information
 * into a single response payload for the {@code GET /api/v1/accounts/{customerId}/summary}
 * endpoint.
 *
 * <p>This object is serialised to JSON and stored in Redis with a TTL of 60 seconds.
 * It must therefore remain Jackson-serialisable at all times — avoid adding non-serialisable
 * fields or circular references.
 *
 * <p><strong>Cache behaviour:</strong> The object is written to Redis after the first DB
 * read. Any subsequent balance change fires an {@code account-update-events} Kafka event
 * which the {@link com.jpmc.cibap.customer.kafka.AccountUpdateEventConsumer} uses to
 * evict the stale key.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>If the Redis TTL expires <em>and</em> the DB is unavailable, the circuit-breaker
 *       in {@link com.jpmc.cibap.customer.service.AccountService} will open and return an
 *       error signal — not a stale summary. Callers should handle this gracefully.</li>
 *   <li>{@code totalBalance} is the arithmetic sum of all active account balances. It
 *       includes CREDIT accounts, which can have negative balances; interpret accordingly.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountSummary {

    /** Identifies which customer this summary belongs to. */
    private UUID customerId;

    /** Ordered list of account details. Empty list is valid (e.g., all accounts CLOSED). */
    private List<AccountDetail> accounts;

    /**
     * Arithmetic sum of {@link AccountDetail#balance} across all active accounts.
     * CREDIT account balances are included and can be negative.
     */
    private BigDecimal totalBalance;

    /**
     * Lightweight per-account view embedded inside the summary response.
     * Intentionally omits sensitive fields (e.g., account number, sort code).
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AccountDetail {

        /** Internal account UUID. */
        private UUID accountId;

        /** Account type: {@code CHECKING}, {@code SAVINGS}, or {@code CREDIT}. */
        private String accountType;

        /**
         * Current balance for this account.
         *
         * <p><strong>⚠ Runtime risk:</strong> This value is a snapshot at cache-write
         * time. It may be up to 60 seconds stale. Do not use for real-time payment
         * decisions without forcing a cache eviction first.
         */
        private BigDecimal balance;

        /** Account lifecycle status at the time the summary was built. */
        private String status;
    }
}
