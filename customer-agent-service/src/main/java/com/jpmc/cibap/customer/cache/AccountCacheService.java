package com.jpmc.cibap.customer.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive Redis cache service for account-related data.
 *
 * <p>This service is the <em>sole</em> point of contact between the business logic and
 * Redis. It encapsulates serialisation, deserialisation, TTL management, and the
 * {@code redis-cache-cb} circuit-breaker.
 *
 * <h2>Why ReactiveStringRedisTemplate instead of ReactiveRedisTemplate?</h2>
 * <p>Spring Boot auto-configures exactly one {@link ReactiveStringRedisTemplate} bean
 * (named {@code reactiveStringRedisTemplate}). It uses UTF-8 {@code StringRedisSerializer}
 * for both keys and values — which is exactly what this cache layer needs. Injecting by
 * the concrete type avoids the ambiguous-bean problem that arises when two
 * {@code ReactiveRedisTemplate<String,String>} beans exist in the context.
 *
 * <h2>Resilience pattern: Circuit Breaker on Redis</h2>
 * <p>Every Redis operation is wrapped with the {@code redis-cache-cb} circuit-breaker
 * (configured in {@link com.jpmc.cibap.customer.config.Resilience4jConfig}).
 *
 * <p><strong>Why:</strong> Redis is an optional cache — if it becomes unavailable, the
 * service must degrade to direct PostgreSQL queries rather than failing the request.
 * Without a circuit-breaker, each Redis timeout adds latency to every request before
 * the service eventually falls back to the DB. With the circuit OPEN, the service
 * immediately skips Redis, keeping p99 latency within SLO during Redis incidents.
 *
 * <h2>Fallback behaviour</h2>
 * <ul>
 *   <li>Cache miss (key absent): returns {@link Mono#empty()} — caller falls back to DB.</li>
 *   <li>Deserialisation error: logs a warning, evicts stale key, returns {@link Mono#empty()}.</li>
 *   <li>Redis unavailable / circuit OPEN: returns {@link Mono#empty()} — bypasses cache entirely.</li>
 * </ul>
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>If both the Redis and DB circuit-breakers open simultaneously (dual failure),
 *       all summary requests will fail with 503. Returning stale financial data is worse
 *       than an explicit error, so this is the correct behaviour.</li>
 *   <li>Cached JSON serialised with an older schema version may fail deserialisation after
 *       a deploy. Evict all keys on breaking schema changes, or use {@code @JsonAlias}
 *       for backwards-compatible field renames.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Service
public class AccountCacheService {

    /**
     * Spring Boot's auto-configured reactive Redis template with String/String serialization.
     * Injected by type — only one {@link ReactiveStringRedisTemplate} bean exists in the context,
     * so no {@code @Qualifier} is needed and there is no ambiguity.
     */
    private final ReactiveStringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * Circuit-breaker for Redis operations. Injected from
     * {@link com.jpmc.cibap.customer.config.Resilience4jConfig#redisCacheCircuitBreaker}.
     *
     * <p>{@code @Qualifier} is required here because multiple {@link CircuitBreaker} beans
     * exist in the context ({@code redis-cache-cb} and {@code account-db-cb}).
     */
    private final CircuitBreaker redisCacheCircuitBreaker;

    public AccountCacheService(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("redisCacheCircuitBreaker") CircuitBreaker redisCacheCircuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisCacheCircuitBreaker = redisCacheCircuitBreaker;
    }

    /**
     * Retrieves and deserialises a cached value by key.
     *
     * <p>Returns {@link Mono#empty()} in all degraded cases (miss, deserialisation error,
     * Redis unavailable) so that callers can use {@code .switchIfEmpty(...)} to fall back
     * to the source of truth.
     *
     * <p><strong>Circuit-breaker:</strong> The Redis GET is wrapped with
     * {@code redis-cache-cb}. If the circuit is OPEN, the operator immediately emits an
     * error, which is caught by {@code onErrorResume} and converted to an empty Mono.
     *
     * @param <T>  the target type to deserialise into
     * @param key  the Redis key; typically {@code account:summary:{customerId}}
     * @param type the class to deserialise the JSON value into
     * @return a {@link Mono} emitting the cached object, or empty on miss/error
     */
    public <T> Mono<T> get(String key, Class<T> type) {
        log.debug("[Cache] GET key={} type={}", key, type.getSimpleName());

        return redisTemplate.opsForValue().get(key)
                // ── Resilience: Circuit-Breaker ──────────────────────────────
                // If Redis is failing, the circuit-breaker opens and this operator
                // immediately emits CallNotPermittedException instead of calling Redis.
                // We catch it below and return empty so the DB fallback kicks in.
                .transform(CircuitBreakerOperator.of(redisCacheCircuitBreaker))

                .flatMap(json -> {
                    try {
                        T value = objectMapper.readValue(json, type);
                        log.debug("[Cache] HIT key={}", key);
                        return Mono.just(value);
                    } catch (Exception e) {
                        // ⚠ Runtime risk: cached JSON schema changed after a deploy.
                        // Safe degradation: evict the stale entry and return empty
                        // so the caller reloads from the DB.
                        log.warn("[Cache] Deserialisation error for key={} type={} — evicting stale entry. Error: {}",
                                key, type.getSimpleName(), e.getMessage());
                        return evict(key).then(Mono.empty());
                    }
                })
                // ── Fallback: Redis unavailable or circuit OPEN ──────────────
                // Any Redis connectivity error or CallNotPermittedException is caught here.
                // We return empty so the calling service falls back to PostgreSQL.
                .onErrorResume(ex -> {
                    log.warn("[Cache] Redis GET failed for key={} — circuit-breaker or connectivity error: {}",
                            key, ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Serialises a value to JSON and stores it in Redis with the given TTL.
     *
     * <p>Failures during write (serialisation error or Redis unavailability) are
     * non-fatal — the service continues without caching and logs the error. The request
     * succeeds; the next fetch will simply miss the cache and reload from the DB.
     *
     * @param <T>        the type of the value to cache
     * @param key        the Redis key
     * @param value      the object to serialise and cache
     * @param ttlSeconds time-to-live in seconds; must be positive
     * @return a {@link Mono} emitting {@code true} on success, {@code false} on failure
     */
    public <T> Mono<Boolean> set(String key, T value, long ttlSeconds) {
        log.debug("[Cache] SET key={} ttl={}s", key, ttlSeconds);

        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // ⚠ Runtime risk: a non-serialisable type was passed to the cache.
            // This is a programming error — log at ERROR so it surfaces in alerts.
            log.error("[Cache] Serialisation error for key={} — value will NOT be cached: {}",
                    key, e.getMessage(), e);
            return Mono.just(false);
        }

        return redisTemplate.opsForValue()
                .set(key, json, Duration.ofSeconds(ttlSeconds))
                // ── Resilience: Circuit-Breaker ──────────────────────────────
                .transform(CircuitBreakerOperator.of(redisCacheCircuitBreaker))
                .doOnSuccess(result -> {
                    if (Boolean.TRUE.equals(result)) {
                        log.debug("[Cache] STORED key={} ttl={}s", key, ttlSeconds);
                    }
                })
                // Cache write failure is non-fatal — return false so the caller knows
                .onErrorResume(ex -> {
                    log.warn("[Cache] Redis SET failed for key={} — circuit-breaker or connectivity error: {}",
                            key, ex.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Removes a cached entry by key.
     *
     * <p>Called by {@link com.jpmc.cibap.customer.kafka.AccountUpdateEventConsumer}
     * when an {@code account-update-events} Kafka message signals a balance change.
     *
     * <p>Eviction failure is non-fatal — a stale entry will expire after its 60-second TTL.
     *
     * @param key the Redis key to delete
     * @return a {@link Mono} emitting {@code true} if the key was deleted, {@code false} if absent
     */
    public Mono<Boolean> evict(String key) {
        log.info("[Cache] EVICT key={}", key);

        return redisTemplate.delete(key)
                .transform(CircuitBreakerOperator.of(redisCacheCircuitBreaker))
                .map(count -> count > 0)
                .onErrorResume(ex -> {
                    log.warn("[Cache] Redis EVICT failed for key={} — stale entry may persist up to TTL: {}",
                            key, ex.getMessage());
                    return Mono.just(false);
                });
    }
}
