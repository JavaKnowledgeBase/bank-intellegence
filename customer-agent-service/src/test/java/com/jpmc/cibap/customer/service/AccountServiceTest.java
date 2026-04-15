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
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountService}.
 *
 * <p>Uses Mockito for dependency mocking and {@link StepVerifier} from reactor-test
 * to assert reactive pipeline behaviour. The Resilience4j circuit-breaker is created
 * in CLOSED state for normal-path tests; specific tests verify open-circuit behaviour.
 *
 * <p><strong>What is tested here:</strong>
 * <ul>
 *   <li>Cache-hit path: Redis returns data → DB is NOT called</li>
 *   <li>Cache-miss path: Redis empty → DB called → Redis written → data returned</li>
 *   <li>AccountNotFoundException when no active accounts exist</li>
 *   <li>DB circuit-breaker OPEN → ServiceUnavailableException emitted</li>
 *   <li>Transaction fetch with date-range validation</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService Unit Tests")
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountCacheService cacheService;

    private CircuitBreaker accountDbCircuitBreaker;
    private AccountService accountService;

    private final UUID CUSTOMER_ID = UUID.randomUUID();
    private final UUID ACCOUNT_ID  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Create a real circuit-breaker in CLOSED state for each test.
        // Tests that require an OPEN circuit will force it open explicitly.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(100.0f)  // Only open after 100% failure rate
                .build();
        accountDbCircuitBreaker = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker("test-db-cb", config);

        accountService = new AccountService(
                accountRepository,
                transactionRepository,
                cacheService,
                accountDbCircuitBreaker
        );
    }

    // ─── getAccountSummary tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("getAccountSummary")
    class GetAccountSummary {

        @Test
        @DisplayName("should return cached summary on Redis HIT — DB must not be called")
        void shouldReturnCachedSummaryOnCacheHit() {
            AccountSummary cached = buildSummary();
            when(cacheService.get(anyString(), eq(AccountSummary.class)))
                    .thenReturn(Mono.just(cached));

            StepVerifier.create(accountService.getAccountSummary(CUSTOMER_ID))
                    .assertNext(summary -> {
                        assertThat(summary.getCustomerId()).isEqualTo(CUSTOMER_ID);
                        assertThat(summary.getTotalBalance()).isEqualByComparingTo("1000.00");
                    })
                    .verifyComplete();

            // DB must not be touched on cache hit
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("should query DB on cache MISS and write result to cache")
        void shouldBuildSummaryFromDbOnCacheMiss() {
            when(cacheService.get(anyString(), eq(AccountSummary.class)))
                    .thenReturn(Mono.empty());
            when(accountRepository.findByCustomerIdAndStatus(CUSTOMER_ID, "ACTIVE"))
                    .thenReturn(Flux.just(buildAccount()));
            when(cacheService.set(anyString(), any(AccountSummary.class), eq(60L)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(accountService.getAccountSummary(CUSTOMER_ID))
                    .assertNext(summary -> {
                        assertThat(summary.getCustomerId()).isEqualTo(CUSTOMER_ID);
                        assertThat(summary.getAccounts()).hasSize(1);
                        assertThat(summary.getTotalBalance()).isEqualByComparingTo("1000.00");
                    })
                    .verifyComplete();

            verify(cacheService).set(anyString(), any(AccountSummary.class), eq(60L));
        }

        @Test
        @DisplayName("should emit AccountNotFoundException when customer has no ACTIVE accounts")
        void shouldThrowAccountNotFoundWhenNoActiveAccounts() {
            when(cacheService.get(anyString(), eq(AccountSummary.class)))
                    .thenReturn(Mono.empty());
            when(accountRepository.findByCustomerIdAndStatus(CUSTOMER_ID, "ACTIVE"))
                    .thenReturn(Flux.empty());

            StepVerifier.create(accountService.getAccountSummary(CUSTOMER_ID))
                    .expectError(AccountNotFoundException.class)
                    .verify();

            // Cache write must NOT be called when an error occurs
            verify(cacheService, never()).set(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("should emit ServiceUnavailableException when DB circuit-breaker is OPEN")
        void shouldEmitServiceUnavailableWhenDbCircuitIsOpen() {
            // Force the circuit-breaker open by recording failures
            when(cacheService.get(anyString(), eq(AccountSummary.class)))
                    .thenReturn(Mono.empty());
            // Mockito returns null by default; provide a real publisher so the CB operator can short-circuit it.
            when(accountRepository.findByCustomerIdAndStatus(CUSTOMER_ID, "ACTIVE"))
                    .thenReturn(Flux.empty());

            // Open the circuit by recording enough failures
            for (int i = 0; i < 10; i++) {
                accountDbCircuitBreaker.onError(0, java.util.concurrent.TimeUnit.NANOSECONDS,
                        new RuntimeException("DB failure"));
            }
            // Circuit should now be OPEN

            StepVerifier.create(accountService.getAccountSummary(CUSTOMER_ID))
                    .expectError(ServiceUnavailableException.class)
                    .verify();
        }

        @Test
        @DisplayName("should still return summary when cache write fails (non-fatal)")
        void shouldReturnSummaryEvenWhenCacheWriteFails() {
            when(cacheService.get(anyString(), eq(AccountSummary.class)))
                    .thenReturn(Mono.empty());
            when(accountRepository.findByCustomerIdAndStatus(CUSTOMER_ID, "ACTIVE"))
                    .thenReturn(Flux.just(buildAccount()));
            // Cache write fails — but response should still succeed
            when(cacheService.set(anyString(), any(AccountSummary.class), eq(60L)))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(accountService.getAccountSummary(CUSTOMER_ID))
                    .assertNext(summary -> assertThat(summary).isNotNull())
                    .verifyComplete();
        }
    }

    // ─── getTransactions tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("getTransactions")
    class GetTransactions {

        private final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");
        private final Instant TO   = Instant.parse("2025-01-31T23:59:59Z");

        @Test
        @DisplayName("should return transactions for all customer accounts")
        void shouldReturnTransactionsForAllAccounts() {
            when(accountRepository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Flux.just(buildAccount()));
            when(transactionRepository.findByAccountIdAndDateRange(ACCOUNT_ID, FROM, TO))
                    .thenReturn(Flux.just(buildTransaction()));

            StepVerifier.create(accountService.getTransactions(CUSTOMER_ID, FROM, TO))
                    .assertNext(tx -> assertThat(tx.getAccountId()).isEqualTo(ACCOUNT_ID))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty flux when customer has no accounts")
        void shouldReturnEmptyWhenNoAccounts() {
            when(accountRepository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Flux.empty());

            StepVerifier.create(accountService.getTransactions(CUSTOMER_ID, FROM, TO))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty flux when no transactions in date range")
        void shouldReturnEmptyWhenNoTransactionsInRange() {
            when(accountRepository.findByCustomerId(CUSTOMER_ID))
                    .thenReturn(Flux.just(buildAccount()));
            when(transactionRepository.findByAccountIdAndDateRange(ACCOUNT_ID, FROM, TO))
                    .thenReturn(Flux.empty());

            StepVerifier.create(accountService.getTransactions(CUSTOMER_ID, FROM, TO))
                    .verifyComplete();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Account buildAccount() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setCustomerId(CUSTOMER_ID);
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setStatus("ACTIVE");
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }

    private AccountSummary buildSummary() {
        return AccountSummary.builder()
                .customerId(CUSTOMER_ID)
                .accounts(java.util.List.of(
                        AccountSummary.AccountDetail.builder()
                                .accountId(ACCOUNT_ID)
                                .accountType("CHECKING")
                                .balance(new BigDecimal("1000.00"))
                                .status("ACTIVE")
                                .build()))
                .totalBalance(new BigDecimal("1000.00"))
                .build();
    }

    private Transaction buildTransaction() {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setAccountId(ACCOUNT_ID);
        tx.setAmount(new BigDecimal("-50.00"));
        tx.setMerchant("Coffee Shop");
        tx.setCategory("FOOD");
        tx.setStatus("CLEARED");
        tx.setCreatedAt(Instant.parse("2025-01-15T10:00:00Z"));
        return tx;
    }
}

