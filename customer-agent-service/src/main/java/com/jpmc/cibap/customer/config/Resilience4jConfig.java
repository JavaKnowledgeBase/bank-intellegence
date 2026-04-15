package com.jpmc.cibap.customer.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

/**
 * Centralised Resilience4j configuration for all circuit-breakers, retries, and
 * rate-limiters used across the Customer Agent Service.
 *
 * <h2>Pattern catalogue</h2>
 *
 * <h3>Circuit Breakers</h3>
 * <ul>
 *   <li><strong>{@code redis-cache-cb}</strong> — wraps all Redis read/write operations.
 *       Opens when Redis becomes unreachable, allowing immediate fallback to PostgreSQL
 *       without waiting for Redis timeouts on every request.</li>
 *   <li><strong>{@code account-db-cb}</strong> — wraps PostgreSQL account queries.
 *       Opens when the database is overloaded, returning a structured 503 instead of
 *       queuing thousands of failing requests that exhaust the R2DBC pool.</li>
 * </ul>
 *
 * <h3>Retry</h3>
 * <ul>
 *   <li><strong>{@code kafka-publish-retry}</strong> — retries Kafka publish operations
 *       on {@link IOException} and {@link org.apache.kafka.common.errors.RetriableException}
 *       with exponential back-off. Prevents transient broker unavailability from surfacing
 *       as a user-visible error.</li>
 * </ul>
 *
 * <h3>Rate Limiter</h3>
 * <ul>
 *   <li><strong>{@code api-rate-limiter}</strong> — limits the account summary endpoint
 *       to 100 requests/second per JVM instance to protect the downstream DB and Redis
 *       from bot-driven spikes.</li>
 * </ul>
 *
 * <h2>Why programmatic configuration over {@code application.yml}?</h2>
 * <p>Programmatic config allows attaching event listeners ({@code onStateTransition},
 * {@code onCallNotPermitted}) at bean creation time, enabling structured logging of
 * circuit-breaker state changes directly to the central log aggregator. YAML config
 * would require a separate AOP setup for the same effect.
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class Resilience4jConfig {

    // ─── Circuit Breaker: Redis ────────────────────────────────────────────────

    /**
     * Circuit-breaker guarding all Redis cache operations.
     *
     * <p><strong>Why we need this:</strong> Redis is an optional hot-path cache. If it
     * becomes unavailable, we must degrade gracefully to PostgreSQL — not fail the entire
     * request. Without this circuit-breaker, each Redis timeout would add ~500 ms latency
     * per request before falling through to the DB.
     *
     * <p>Configuration rationale:
     * <ul>
     *   <li>Sliding window: 10 calls — fast detection for a low-traffic warm-up period</li>
     *   <li>Failure threshold: 50% — opens after 5/10 failures</li>
     *   <li>Wait in open state: 10 s — short enough for Redis pod restarts to be detected</li>
     *   <li>Half-open probe: 3 calls — probe Redis health before fully closing</li>
     * </ul>
     *
     * @param registry the global {@link CircuitBreakerRegistry}
     * @return configured circuit-breaker for Redis
     */
    @Bean
    public CircuitBreaker redisCacheCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50.0f)          // Open if ≥ 50% of 10 calls fail
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only count genuine exceptions as failures (not empty Monos)
                .recordExceptions(Exception.class)
                .build();

        CircuitBreaker cb = registry.circuitBreaker("redis-cache-cb", config);

        // Structured log on every state transition for operations observability
        cb.getEventPublisher().onStateTransition(event ->
                log.warn("[CircuitBreaker] redis-cache-cb transitioned: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState())
        );
        cb.getEventPublisher().onCallNotPermitted(event ->
                log.warn("[CircuitBreaker] redis-cache-cb call NOT PERMITTED — circuit is OPEN")
        );

        return cb;
    }

    /**
     * Circuit-breaker guarding all PostgreSQL account-related queries.
     *
     * <p><strong>Why we need this:</strong> If the DB is overloaded (e.g., during a
     * migration or hardware failure), unbounded request queuing will exhaust the R2DBC
     * connection pool and cause memory pressure. The circuit-breaker fast-fails requests
     * when the DB error rate is high, giving the DB breathing room to recover.
     *
     * <p>Configuration rationale:
     * <ul>
     *   <li>Sliding window: 20 calls — larger window for more stable failure rate signal</li>
     *   <li>Failure threshold: 60% — higher tolerance than Redis because a partial DB
     *       outage (e.g., read replica failing) may still allow some writes through</li>
     *   <li>Wait in open state: 30 s — longer than Redis; DB restarts take more time</li>
     * </ul>
     *
     * @param registry the global {@link CircuitBreakerRegistry}
     * @return configured circuit-breaker for PostgreSQL
     */
    @Bean
    public CircuitBreaker accountDbCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .failureRateThreshold(60.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(Exception.class)
                .build();

        CircuitBreaker cb = registry.circuitBreaker("account-db-cb", config);

        cb.getEventPublisher().onStateTransition(event ->
                log.error("[CircuitBreaker] account-db-cb transitioned: {} → {} — DATABASE HEALTH DEGRADED",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState())
        );
        cb.getEventPublisher().onCallNotPermitted(event ->
                log.error("[CircuitBreaker] account-db-cb call NOT PERMITTED — circuit is OPEN")
        );

        return cb;
    }

    // ─── Retry: Kafka ─────────────────────────────────────────────────────────

    /**
     * Retry policy for Kafka support-event publish operations.
     *
     * <p><strong>Why we need this:</strong> Kafka brokers can be temporarily unavailable
     * during rolling restarts or leader elections. A single transient failure should not
     * surface as a 500 to the customer. Three retries with exponential back-off cover the
     * typical 2–5 second leader-election window.
     *
     * <p>Configuration rationale:
     * <ul>
     *   <li>Max attempts: 3 — after 3 failures we surface the error for circuit-breaker handling</li>
     *   <li>Wait duration: 200 ms initial, doubles on each attempt (200 ms → 400 ms → 800 ms)</li>
     *   <li>Only retry on {@link IOException} and retriable Kafka exceptions — not on
     *       {@link com.fasterxml.jackson.core.JsonProcessingException}, which is a
     *       programming error that retrying would not fix</li>
     * </ul>
     *
     * @param registry the global {@link RetryRegistry}
     * @return configured retry for Kafka
     */
    @Bean
    public Retry kafkaPublishRetry(RetryRegistry registry) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(IOException.class,
                        org.apache.kafka.common.errors.RetriableException.class)
                // Never retry serialisation errors — they are bugs, not transient failures
                .ignoreExceptions(com.fasterxml.jackson.core.JsonProcessingException.class)
                .build();

        Retry retry = registry.retry("kafka-publish-retry", config);

        retry.getEventPublisher().onRetry(event ->
                log.warn("[Retry] kafka-publish-retry attempt #{} after error: {}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable() != null
                                ? event.getLastThrowable().getMessage() : "unknown")
        );
        retry.getEventPublisher().onError(event ->
                log.error("[Retry] kafka-publish-retry EXHAUSTED after {} attempts",
                        event.getNumberOfRetryAttempts())
        );

        return retry;
    }

    // ─── Rate Limiter: API ────────────────────────────────────────────────────

    /**
     * Rate-limiter for the public account-summary and transaction-list API endpoints.
     *
     * <p><strong>Why we need this:</strong> The account summary endpoint triggers a Redis
     * read (and on miss, a DB query). Without rate limiting, a bot or misconfigured client
     * could saturate the connection pool within seconds. The rate limiter acts as the first
     * line of defence before requests reach any downstream system.
     *
     * <p>Configuration rationale:
     * <ul>
     *   <li>Limit for period: 100 requests per 1 second per JVM instance
     *       (with 3 replicas = 300 rps cluster-wide)</li>
     *   <li>Timeout: 0 ms — immediately reject excess requests with HTTP 429 rather than
     *       queuing them (queueing would just shift the problem downstream)</li>
     * </ul>
     *
     * <p><strong>⚠ Note:</strong> This is a per-JVM limiter. For true distributed rate
     * limiting, replace with a Redis-backed token bucket (e.g., Bucket4j + Redis).
     *
     * @param registry the global {@link RateLimiterRegistry}
     * @return configured rate-limiter for the public API
     */
    @Bean
    public RateLimiter apiRateLimiter(RateLimiterRegistry registry) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)  // Reject immediately; don't queue
                .build();

        RateLimiter rl = registry.rateLimiter("api-rate-limiter", config);

        rl.getEventPublisher().onFailure(event ->
                log.warn("[RateLimiter] api-rate-limiter rejected a request — limit exceeded")
        );

        return rl;
    }
}
