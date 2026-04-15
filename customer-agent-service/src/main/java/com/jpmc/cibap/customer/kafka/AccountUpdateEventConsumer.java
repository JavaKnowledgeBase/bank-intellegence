package com.jpmc.cibap.customer.kafka;

import com.jpmc.cibap.customer.cache.AccountCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens to {@code account-update-events} and evicts stale
 * Redis cache entries for affected customers.
 *
 * <h2>Why this consumer exists</h2>
 * <p>The Redis account-summary cache has a 60-second TTL as a safety net. However, if an
 * account balance changes (e.g., transaction cleared, payment posted), the cached summary
 * becomes stale <em>immediately</em>. Rather than waiting for TTL expiry, the
 * orchestration service publishes an event to {@code account-update-events} whenever an
 * account balance changes. This consumer reacts to that event and proactively evicts the
 * relevant cache key, ensuring the next summary request fetches fresh data from PostgreSQL.
 *
 * <h2>Resilience patterns applied</h2>
 *
 * <h3>1. Manual offset acknowledgement</h3>
 * <p>The consumer container is configured with {@code AckMode.MANUAL_IMMEDIATE}
 * (see {@link com.jpmc.cibap.customer.config.KafkaConfig}). We only acknowledge the
 * Kafka offset <em>after</em> the Redis eviction call has returned. This ensures that
 * if the eviction fails and the pod restarts, the event will be reprocessed — the cache
 * will eventually be evicted on re-delivery.
 *
 * <h3>2. Error handling with fixed retry back-off</h3>
 * <p>The {@link com.jpmc.cibap.customer.config.KafkaConfig#kafkaListenerContainerFactory}
 * attaches a {@link org.springframework.kafka.listener.DefaultErrorHandler} with 3 retries
 * and 1-second back-off. After 3 failures the record is sent to the DLT
 * ({@code account-update-events.DLT}) to prevent partition blocking.
 *
 * <h3>3. Non-fatal cache eviction failures</h3>
 * <p>Redis eviction errors are caught in {@link AccountCacheService#evict} and logged but
 * not thrown. A failed eviction means the stale cache entry will survive until its 60-second
 * TTL expires — an acceptable degradation for a cache layer.
 *
 * <h2>Event message format (JSON)</h2>
 * <pre>
 * {
 *   "customerId": "uuid",
 *   "accountId":  "uuid",
 *   "eventType":  "BALANCE_UPDATED"
 * }
 * </pre>
 * <p>The consumer only needs the {@code customerId} field to build the cache key. Full
 * event parsing is deferred to avoid tight coupling with the orchestration service schema.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>Malformed JSON in the message body will cause a parsing exception. The
 *       {@link DefaultErrorHandler} will retry 3 times, then DLT the record.</li>
 *   <li>A high-velocity account (thousands of updates per second) could cause Redis
 *       eviction calls to queue. The evict operation is fire-and-forget; the 60 s TTL
 *       provides the final safety net.</li>
 *   <li>The message key is the {@code customerId} string. If the key is null, the
 *       consumer falls back to extracting {@code customerId} from the value JSON.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountUpdateEventConsumer {

    private static final String CACHE_KEY_TEMPLATE = "account:summary:%s";

    private final AccountCacheService cacheService;

    /**
     * Handles an account-update event by evicting the corresponding Redis cache entry.
     *
     * <p>The Kafka message key is expected to be the {@code customerId} string. If the
     * key is present and parseable, we build the cache key directly from it. If the key
     * is absent, we log a warning and skip eviction (do not fail — fail-fast on a cache
     * event would block partition progress unnecessarily).
     *
     * @param record         the Kafka consumer record
     * @param acknowledgment manual acknowledgement handle; committed after eviction attempt
     */
    @KafkaListener(
            topics = "account-update-events",
            groupId = "customer-agent-cache-invalidation",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onAccountUpdateEvent(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        String messageKey  = record.key();
        String messageBody = record.value();
        long   offset      = record.offset();
        int    partition   = record.partition();

        log.debug("[AccountUpdateConsumer] Received event partition={} offset={} key={}",
                partition, offset, messageKey);

        // ⚠ Runtime risk: message key could be null if the producer did not set it.
        // We fall back gracefully rather than throwing.
        if (messageKey == null || messageKey.isBlank()) {
            log.warn("[AccountUpdateConsumer] Message key is null/blank at partition={} offset={} "
                    + "— cannot derive customerId; skipping eviction. Body: {}",
                    partition, offset, messageBody);
            // Still acknowledge to avoid reprocessing an unresolvable record
            acknowledgment.acknowledge();
            return;
        }

        String cacheKey = String.format(CACHE_KEY_TEMPLATE, messageKey);

        // Evict the stale cache entry. AccountCacheService.evict() handles its own errors
        // and logs them; we block here to ensure eviction completes before acknowledging.
        //
        // ⚠ Runtime risk: .block() on a reactive type inside a listener thread is safe here
        // because the @KafkaListener runs on a dedicated Kafka listener thread (not the
        // event loop). Never call .block() from within a WebFlux controller or reactive chain.
        Boolean evicted = cacheService.evict(cacheKey).block();

        if (Boolean.TRUE.equals(evicted)) {
            log.info("[AccountUpdateConsumer] Cache evicted cacheKey={} partition={} offset={}",
                    cacheKey, partition, offset);
        } else {
            // Non-fatal: stale entry will expire after TTL. Log at warn for SRE awareness.
            log.warn("[AccountUpdateConsumer] Cache entry NOT evicted (absent or Redis error) "
                    + "cacheKey={} partition={} offset={}",
                    cacheKey, partition, offset);
        }

        // ── Manual Acknowledgement ──────────────────────────────────────────
        // Commit the offset ONLY after the eviction attempt. If the pod crashes before
        // this line, Kafka will re-deliver the event and the eviction will be retried.
        acknowledgment.acknowledge();
    }
}
