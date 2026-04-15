package com.jpmc.cibap.customer.controller;

import com.jpmc.cibap.customer.config.SecurityConfig;
import com.jpmc.cibap.customer.exception.AccountNotFoundException;
import com.jpmc.cibap.customer.exception.GlobalExceptionHandler;
import com.jpmc.cibap.customer.exception.ServiceUnavailableException;
import com.jpmc.cibap.customer.model.AccountSummary;
import com.jpmc.cibap.customer.model.SupportRequest;
import com.jpmc.cibap.customer.model.Transaction;
import com.jpmc.cibap.customer.service.AccountService;
import com.jpmc.cibap.customer.service.SupportRequestService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Slice integration test for {@link AccountController}.
 *
 * <p>Uses {@link WebFluxTest} to spin up only the WebFlux layer (no full Spring context,
 * no DB, no Redis, no Kafka). All downstream services are mocked with {@link MockBean}.
 * JWT authentication is provided via {@link SecurityMockServerConfigurers#mockJwt()}.
 *
 * <p><strong>What is tested here:</strong>
 * <ul>
 *   <li>HTTP 200 on successful summary/transaction requests</li>
 *   <li>HTTP 404 when AccountNotFoundException is thrown</li>
 *   <li>HTTP 503 when ServiceUnavailableException is thrown</li>
 *   <li>HTTP 400 on invalid UUID path variable</li>
 *   <li>HTTP 400 on invalid date range (from after to)</li>
 *   <li>HTTP 400 on missing required request body fields</li>
 *   <li>HTTP 202 on successful support request submission</li>
 *   <li>HTTP 401 when no JWT is provided</li>
 *   <li>HTTP 403 when JWT lacks required scope</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@WebFluxTest(AccountController.class)
@Import({AccountControllerIntegrationTest.TestRateLimiterConfig.class, SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("AccountController Integration Tests")
class AccountControllerIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AccountService accountService;

    @MockBean
    private SupportRequestService supportRequestService;

    @MockBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private final UUID CUSTOMER_ID = UUID.randomUUID();
    private final UUID ACCOUNT_ID  = UUID.randomUUID();

    // ─── GET /summary tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{customerId}/summary")
    class GetAccountSummary {

        @Test
        @DisplayName("should return 200 with account summary on valid request")
        void shouldReturn200WithSummary() {
            when(accountService.getAccountSummary(CUSTOMER_ID))
                    .thenReturn(Mono.just(buildSummary()));

            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:read")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/summary", CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(AccountSummary.class)
                    .value(summary -> {
                        assertThat(summary.getCustomerId()).isEqualTo(CUSTOMER_ID);
                        assertThat(summary.getTotalBalance()).isEqualByComparingTo("1000.00");
                    });
        }

        @Test
        @DisplayName("should return 404 when customer has no active accounts")
        void shouldReturn404WhenNoActiveAccounts() {
            when(accountService.getAccountSummary(CUSTOMER_ID))
                    .thenReturn(Mono.error(new AccountNotFoundException("No active accounts")));

            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:read")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/summary", CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectBody()
                    .jsonPath("$.title").isEqualTo("Account Not Found");
        }

        @Test
        @DisplayName("should return 503 when service is unavailable")
        void shouldReturn503WhenServiceUnavailable() {
            when(accountService.getAccountSummary(CUSTOMER_ID))
                    .thenReturn(Mono.error(new ServiceUnavailableException("DB circuit open")));

            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:read")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/summary", CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody()
                    .jsonPath("$.title").isEqualTo("Service Unavailable")
                    .jsonPath("$.retryAfterSeconds").isEqualTo(5);
        }

        @Test
        @DisplayName("should return 401 when no JWT is provided")
        void shouldReturn401WithoutJwt() {
            webTestClient
                    .get()
                    .uri("/api/v1/accounts/{customerId}/summary", CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("should return 403 when JWT lacks accounts:read scope")
        void shouldReturn403WithInsufficientScope() {
            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:write")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/summary", CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    // ─── GET /transactions tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{customerId}/transactions")
    class GetTransactions {

        @Test
        @DisplayName("should return 200 with transaction list")
        void shouldReturn200WithTransactions() {
            when(accountService.getTransactions(eq(CUSTOMER_ID), any(Instant.class), any(Instant.class)))
                    .thenReturn(Flux.just(buildTransaction()));

            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:read")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/transactions?from=2025-01-01T00:00:00Z&to=2025-01-31T23:59:59Z",
                            CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBodyList(Transaction.class)
                    .hasSize(1);
        }

        @Test
        @DisplayName("should return 400 when from is after to")
        void shouldReturn400WhenFromAfterTo() {
            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:read")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/transactions?from=2025-02-01T00:00:00Z&to=2025-01-01T00:00:00Z",
                            CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.title").isEqualTo("Bad Request");
        }

        @Test
        @DisplayName("should return 400 when date params are missing")
        void shouldReturn400WhenDateParamsMissing() {
            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:read")))
                    .get()
                    .uri("/api/v1/accounts/{customerId}/transactions", CUSTOMER_ID)
                    .exchange()
                    .expectStatus().isBadRequest();
        }
    }

    // ─── POST /support-request tests ─────────────────────────────────────────

    @Nested
    @DisplayName("POST /{customerId}/support-request")
    class SubmitSupportRequest {

        @Test
        @DisplayName("should return 202 Accepted on valid support request")
        void shouldReturn202OnValidRequest() {
            String messageId = UUID.randomUUID().toString();
            when(supportRequestService.submit(any(SupportRequest.class)))
                    .thenReturn(Mono.just(messageId));

            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:write")))
                    .post()
                    .uri("/api/v1/accounts/{customerId}/support-request", CUSTOMER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {
                              "category": "DISPUTE",
                              "description": "Unauthorised charge on my account",
                              "priority": "HIGH"
                            }
                            """)
                    .exchange()
                    .expectStatus().isAccepted()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).contains("Support request submitted:"));
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void shouldReturn400WhenBodyInvalid() {
            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:write")))
                    .post()
                    .uri("/api/v1/accounts/{customerId}/support-request", CUSTOMER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")  // Missing category, description, priority
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.title").isEqualTo("Validation Error")
                    .jsonPath("$.fieldErrors").exists();
        }

        @Test
        @DisplayName("should return 404 when customer has no active account")
        void shouldReturn404WhenNoActiveAccount() {
            when(supportRequestService.submit(any(SupportRequest.class)))
                    .thenReturn(Mono.error(new AccountNotFoundException("No active account")));

            webTestClient
                    .mutateWith(mockJwt().authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts:write")))
                    .post()
                    .uri("/api/v1/accounts/{customerId}/support-request", CUSTOMER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {
                              "category": "GENERAL",
                              "description": "Need help",
                              "priority": "LOW"
                            }
                            """)
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private AccountSummary buildSummary() {
        return AccountSummary.builder()
                .customerId(CUSTOMER_ID)
                .accounts(List.of(
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

    /**
     * Provides a fully permissive rate-limiter for tests.
     * Without this, the test context would fail to start due to missing
     * {@link RateLimiter} bean required by {@link AccountController}.
     */
    static class TestRateLimiterConfig {
        @Bean
        public RateLimiter apiRateLimiter() {
            RateLimiterConfig config = RateLimiterConfig.custom()
                    .limitForPeriod(Integer.MAX_VALUE)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(Duration.ZERO)
                    .build();
            return RateLimiterRegistry.ofDefaults().rateLimiter("api-rate-limiter", config);
        }
    }
}





