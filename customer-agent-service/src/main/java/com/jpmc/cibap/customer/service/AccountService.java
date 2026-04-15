package com.jpmc.cibap.customer.service;

import com.jpmc.cibap.customer.cache.AccountCacheService;
import com.jpmc.cibap.customer.exception.AccountNotFoundException;
import com.jpmc.cibap.customer.exception.ServiceUnavailableException;
import com.jpmc.cibap.customer.model.Account;
import com.jpmc.cibap.customer.model.AccountSummary;
import com.jpmc.cibap.customer.model.Transaction;
import com.jpmc.cibap.customer.repository.AccountRepository;
import com.jpmc.cibap.customer.repository.TransactionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core business logic for account summary retrieval and transaction history queries.
 *
 * <h2>Cache-aside pattern (Redis + PostgreSQL)</h2>
 * <pre>
 * Request
 *   └─► Redis (account:summary:{customerId})
 *         ├─► HIT  → return cached AccountSummary
 *         └─► MISS → PostgreSQL → build AccountSummary → write to Redis → return
 * </pre>
 *
 * <h2>Resilience patterns applied</h2>
 *
 * <h3>1. Circuit Breaker — {@code account-db-cb}</h3>
 * <p>Wraps all PostgreSQL queries. If the database error rate exceeds 60% within a
 * 20-call sliding window, the circuit opens and subsequent calls immediately receive
 * a {@link ServiceUnavailableException} instead of waiting for timeouts. This protects
 * the R2DBC connection pool from exhaustion during DB incidents.
 *
 * <h3>2. Fallback chain for account summary</h3>
 * <ol>
 *   <li>Try Redis cache (via {@link AccountCacheService#get} — itself circuit-broken)</li>
 *   <li>On miss or Redis unavailability, try PostgreSQL (circuit-broken)</li>
 *   <li>If PostgreSQL circuit is OPEN, return {@link ServiceUnavailableException}</li>
 * </ol>
 *
 * <h3>3. Timeout on DB queries</h3>
 * <p>Each DB subscription has a 5-second reactive timeout. Without this, a slow DB
 * query would hold a Reactor thread context indefinitely, eventually starving the
 * event loop.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>{@link AccountNotFoundException} — thrown when a customer has no ACTIVE accounts.
 *       This is a <em>business</em> exception, not a DB failure; it will not trip the
 *       circuit-breaker.</li>
 *   <li>{@link org.springframework.dao.OptimisticLockingFailureException} — concurrent
 *       updates to the same account row will propagate as an error signal. The caller
 *       (e.g., an account-update consumer) must retry.</li>
 *   <li>Empty flux from {@code findByCustomerId} is legitimate (customer has no accounts)
 *       and is handled by returning an empty transaction list, not an error.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountCacheService cacheService;

    /**
     * Circuit-breaker for PostgreSQL queries. Injected from
     * {@link com.jpmc.cibap.customer.config.Resilience4jConfig#accountDbCircuitBreaker}.
     */
    private final CircuitBreaker accountDbCircuitBreaker;

    /** Redis cache key template for account summaries. */
    private static final String CACHE_KEY_TEMPLATE = "account:summary:%s";

    /** Timeout on DB queries to prevent event-loop starvation. */
    private static final java.time.Duration DB_TIMEOUT = java.time.Duration.ofSeconds(5);

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns an aggregated account summary for the given customer.
     *
     * <p>Implements the cache-aside pattern:
     * <ol>
     *   <li>Attempt to load from Redis (circuit-broken by {@link AccountCacheService})</li>
     *   <li>On cache miss, query PostgreSQL (circuit-broken by {@code account-db-cb})</li>
     *   <li>Write the result to Redis (TTL 60 s) and return</li>
     * </ol>
     *
     * @param customerId the customer UUID; must not be {@code null}
     * @return a {@link Mono} emitting the account summary
     * @throws AccountNotFoundException   if the customer has no ACTIVE accounts
     * @throws ServiceUnavailableException if the DB circuit-breaker is OPEN
     */
    public Mono<AccountSummary> getAccountSummary(UUID customerId) {
        String cacheKey = String.format(CACHE_KEY_TEMPLATE, customerId);

        log.debug("[AccountService] getAccountSummary customerId={}", customerId);

        return cacheService.get(cacheKey, AccountSummary.class)

                // ── Cache MISS path ───────────────────────────────────────────
                .switchIfEmpty(Mono.defer(() ->
                        buildSummaryFromDb(customerId)
                                .flatMap(summary -> cacheService
                                        .set(cacheKey, summary, 60)
                                        .doOnSuccess(cached -> {
                                            if (Boolean.TRUE.equals(cached)) {
                                                log.debug("[AccountService] Summary cached key={}", cacheKey);
                                            } else {
                                                // Non-fatal: Redis write failed; response still returned
                                                log.warn("[AccountService] Summary NOT cached (Redis write failed) key={}",
                                                        cacheKey);
                                            }
                                        })
                                        .thenReturn(summary))
                ))

                // ── Observability ─────────────────────────────────────────────
                .doOnSuccess(s -> log.info("[AccountService] Summary returned customerId={} accounts={}",
                        customerId,
                        s.getAccounts() != null ? s.getAccounts().size() : 0))

                .doOnError(ex -> log.error("[AccountService] getAccountSummary failed customerId={}: {}",
                        customerId, ex.getMessage(), ex));
    }

    /**
     * Returns up to 100 transactions per account for the given customer within the
     * specified date range, ordered newest-first.
     *
     * <p>Internally resolves all account IDs for the customer, then queries the
     * {@code transactions} table per account. Accounts with no transactions in the range
     * contribute zero rows.
     *
     * <p><strong>Circuit-breaker:</strong> Both the account-lookup and transaction queries
     * are wrapped with {@code account-db-cb}. A single open circuit fails all in-flight
     * queries immediately.
     *
     * <p><strong>⚠ Runtime risk:</strong> A customer with many accounts (e.g., 50) will
     * issue 50 parallel DB queries. Under extreme cardinality this could spike the R2DBC
     * pool. Consider adding {@code .limitRate(10)} to the inner flatMap for very large
     * customer portfolios.
     *
     * @param customerId the customer UUID
     * @param from       inclusive lower time bound; must not be {@code null}
     * @param to         inclusive upper time bound; must be after {@code from}
     * @return a {@link Flux} emitting transactions, or empty if none found
     * @throws ServiceUnavailableException if the DB circuit-breaker is OPEN
     */
    public Flux<Transaction> getTransactions(UUID customerId, Instant from, Instant to) {
        log.debug("[AccountService] getTransactions customerId={} from={} to={}", customerId, from, to);

        // ⚠ Runtime risk: if from > to, both the DB query and the partition pruning
        // will return zero rows without error. We validate this at the controller layer.

        return accountRepository.findByCustomerId(customerId)
                // ── Resilience: Circuit-Breaker (DB) ─────────────────────────
                .transform(CircuitBreakerOperator.of(accountDbCircuitBreaker))

                .flatMap(account -> {
                    log.debug("[AccountService] Fetching transactions for accountId={}", account.getId());
                    return transactionRepository.findByAccountIdAndDateRange(
                            account.getId(), from, to);
                })

                // ── Resilience: Timeout ───────────────────────────────────────
                // ⚠ Runtime risk: without this, a slow query holds the event loop slot indefinitely
                .timeout(DB_TIMEOUT)

                // ── Fallback: Circuit OPEN ────────────────────────────────────
                .onErrorMap(CallNotPermittedException.class, ex -> {
                    log.error("[AccountService] DB circuit-breaker OPEN — getTransactions customerId={}",
                            customerId);
                    return new ServiceUnavailableException(
                            "Account service is temporarily unavailable. Please retry shortly.", ex);
                })

                .doOnError(ex -> log.error("[AccountService] getTransactions failed customerId={}: {}",
                        customerId, ex.getMessage(), ex))

                .doOnComplete(() -> log.debug("[AccountService] getTransactions complete customerId={}",
                        customerId));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds an {@link AccountSummary} from PostgreSQL data.
     *
     * <p>Fetches only {@code ACTIVE} accounts. If no active accounts exist, emits
     * an {@link AccountNotFoundException} (a business error, not a DB failure).
     *
     * <p><strong>Circuit-breaker:</strong> Wrapped with {@code account-db-cb}. On OPEN,
     * immediately emits {@link ServiceUnavailableException}.
     *
     * @param customerId the customer UUID
     * @return a {@link Mono} emitting the built summary
     */
    @Transactional(readOnly = true)
    Mono<AccountSummary> buildSummaryFromDb(UUID customerId) {
        log.debug("[AccountService] Cache miss — querying DB for customerId={}", customerId);

        return accountRepository.findByCustomerIdAndStatus(customerId, "ACTIVE")
                // ── Resilience: Circuit-Breaker (DB) ─────────────────────────
                // If the DB error rate is too high, the CB opens and immediately
                // emits CallNotPermittedException — callers convert it to 503.
                .transform(CircuitBreakerOperator.of(accountDbCircuitBreaker))

                // ── Resilience: Timeout ───────────────────────────────────────
                .timeout(DB_TIMEOUT)

                // An empty Flux (no active accounts) is a business error, not a DB error.
                // It will NOT trip the circuit-breaker.
                .switchIfEmpty(Flux.error(
                        new AccountNotFoundException(
                                "No active accounts found for customer: " + customerId)))

                .collectList()
                .map(this::toAccountSummary)

                // ── Fallback: Circuit OPEN ────────────────────────────────────
                .onErrorMap(CallNotPermittedException.class, ex -> {
                    log.error("[AccountService] DB circuit-breaker OPEN — buildSummaryFromDb customerId={}",
                            customerId);
                    return new ServiceUnavailableException(
                            "Account data is temporarily unavailable. Please retry shortly.", ex);
                });
    }

    /**
     * Maps a list of {@link Account} entities to an {@link AccountSummary} DTO.
     *
     * <p><strong>⚠ Runtime risk:</strong> {@link BigDecimal#add} can produce a result
     * with unbounded scale if the inputs have high precision. The {@code NUMERIC(18,2)}
     * column constraint bounds this in practice, but callers should never pass raw
     * application-computed BigDecimals to this method.
     *
     * @param accounts list of active accounts; must not be {@code null}
     * @return the aggregated {@link AccountSummary}
     */
    private AccountSummary toAccountSummary(List<Account> accounts) {
        BigDecimal totalBalance = accounts.stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AccountSummary.AccountDetail> details = accounts.stream()
                .map(a -> AccountSummary.AccountDetail.builder()
                        .accountId(a.getId())
                        .accountType(a.getAccountType())
                        .balance(a.getBalance())
                        .status(a.getStatus())
                        .build())
                .toList();

        UUID customerId = accounts.getFirst().getCustomerId();

        log.debug("[AccountService] Built summary customerId={} accounts={} totalBalance={}",
                customerId, details.size(), totalBalance);

        return AccountSummary.builder()
                .customerId(customerId)
                .accounts(details)
                .totalBalance(totalBalance)
                .build();
    }
}

