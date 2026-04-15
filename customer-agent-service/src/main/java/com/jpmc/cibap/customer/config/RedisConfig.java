package com.jpmc.cibap.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis-related bean configuration for the account-summary cache.
 *
 * <h2>Why no custom ReactiveRedisTemplate?</h2>
 * <p>Spring Boot's {@code RedisReactiveAutoConfiguration} already provides a
 * {@link org.springframework.data.redis.core.ReactiveStringRedisTemplate} bean
 * (named {@code reactiveStringRedisTemplate}) that uses UTF-8 String serialization
 * for both keys and values — identical to what a custom bean would configure.
 * Declaring a second {@code ReactiveRedisTemplate<String,String>} bean creates an
 * ambiguous-bean conflict at startup. We therefore rely on the auto-configured bean
 * and inject it by type ({@code ReactiveStringRedisTemplate}) in
 * {@link com.jpmc.cibap.customer.cache.AccountCacheService}, which guarantees a
 * single unambiguous match without needing {@code @Qualifier}.
 *
 * <h2>Serialisation strategy</h2>
 * <p>Both keys and values are stored as UTF-8 JSON strings. The {@link ObjectMapper}
 * bean defined here is configured with:
 * <ul>
 *   <li>{@link JavaTimeModule} — serialises {@link java.time.Instant} as ISO-8601 strings</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS = false} — human-readable date strings in cache</li>
 * </ul>
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>If the Redis host is unreachable at startup, the connection factory will throw on
 *       the first operation (lazy connection). The circuit-breaker in
 *       {@link com.jpmc.cibap.customer.cache.AccountCacheService} prevents this from
 *       cascading into a full service outage.</li>
 *   <li>Changing the JSON structure of cached classes (e.g., renaming a field) will cause
 *       deserialization failures on cached entries until their TTL expires. Use
 *       {@code @JsonAlias} for backwards-compatible renames.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Configures a Jackson {@link ObjectMapper} for JSON serialisation of cached values.
     *
     * <p>This bean is shared with {@link com.jpmc.cibap.customer.cache.AccountCacheService}
     * and {@link com.jpmc.cibap.customer.kafka.SupportRequestProducer}.
     *
     * @return a configured {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Register JSR-310 module so Instant, LocalDate etc. serialise correctly
        mapper.registerModule(new JavaTimeModule());
        // Use ISO-8601 strings instead of epoch milliseconds for readability in cache
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        log.info("Jackson ObjectMapper configured with JavaTimeModule");
        return mapper;
    }
}
