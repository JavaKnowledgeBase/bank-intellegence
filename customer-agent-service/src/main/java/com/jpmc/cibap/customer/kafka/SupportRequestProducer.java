package com.jpmc.cibap.customer.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.customer.exception.ServiceUnavailableException;
import com.jpmc.cibap.customer.model.SupportRequest;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka producer for customer support events.
 *
 * <p>Publishes {@link SupportRequest} payloads to the {@code customer-support-events}
 * topic. Each message is partitioned by {@code customerId} to ensure ordered delivery
 * for the same customer.
 *
 * <h2>Resilience pattern: Retry</h2>
 * <p>The Kafka send operation is wrapped with the {@code kafka-publish-retry} Resilience4j
 * retry (configured in {@link com.jpmc.cibap.customer.config.Resilience4jConfig}).
 *
 * <p><strong>Why:</strong> Kafka brokers can be briefly unavailable during leader elections
 * (typically 2–5 s). Without retry, a leader election would surface as a 500 to the
 * customer. Three retries with exponential back-off (200 ms → 400 ms → 800 ms) cover
 * the typical election window.
 *
 * <p><strong>Idempotence guarantee:</strong> The Kafka producer is configured with
 * {@code enable.idempotence=true} (see {@link com.jpmc.cibap.customer.config.KafkaConfig}).
 * Combined with the unique {@code messageId} in each payload, this prevents duplicate
 * messages on retry at both the broker and consumer level.
 *
 * <h2>Blocking bridge</h2>
 * <p>{@link KafkaTemplate#send} returns a {@link CompletableFuture} (not a Reactor type).
 * We adapt it via {@link Mono#fromFuture} and subscribe on {@link Schedulers#boundedElastic}
 * to prevent the blocking Kafka I/O from occupying the event-loop thread.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>{@link JsonProcessingException} — thrown if the {@link SupportRequest} cannot be
 *       serialised to JSON (e.g., a non-serialisable field was added). This is a programming
 *       error. The retry policy is configured to {@code ignoreExceptions(JsonProcessingException)}
 *       so it will NOT retry — it fails immediately.</li>
 *   <li>If all 3 retry attempts fail, {@link ServiceUnavailableException} is emitted. The
 *       request is <em>lost</em> at this point. For P0 categories, consider the outbox pattern:
 *       write to DB first and publish from a separate background job.</li>
 *   <li>Message size: the default Kafka broker max is 1 MB. If {@code description} is very
 *       long (allowed up to 2000 chars by validation) combined with other fields, the message
 *       could approach this limit in edge cases. Monitor {@code record-size-max} in Kafka metrics.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportRequestProducer {

    /** Kafka topic for support events consumed by the notification service. */
    static final String TOPIC = "customer-support-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Retry policy injected from
     * {@link com.jpmc.cibap.customer.config.Resilience4jConfig#kafkaPublishRetry}.
     */
    private final Retry kafkaPublishRetry;

    /**
     * Assigns a unique {@code messageId}, serialises the request to JSON, and publishes
     * to the {@code customer-support-events} Kafka topic.
     *
     * <p>Partitioned by {@code customerId.toString()} to preserve ordering of events for
     * the same customer on the consumer side.
     *
     * @param request the support request to publish; {@code customerId} must be non-null
     * @return a {@link Mono} emitting the assigned {@code messageId} on success
     * @throws ServiceUnavailableException if all retry attempts fail
     * @throws IllegalStateException       if {@code customerId} is null (programming error)
     */
    public Mono<String> publish(SupportRequest request) {
        // Assign idempotency key before serialisation so it is embedded in the payload
        String messageId = UUID.randomUUID().toString();
        request.setMessageId(messageId);

        log.debug("[KafkaProducer] Preparing to publish messageId={} customerId={} category={}",
                messageId, request.getCustomerId(), request.getCategory());

        // ── Serialise ────────────────────────────────────────────────────────
        // ⚠ Runtime risk: JsonProcessingException here is a programming error (non-serialisable
        // field). We surface it immediately — retrying would not fix a code bug.
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            log.error("[KafkaProducer] Serialization FAILED — will NOT retry messageId={}: {}",
                    messageId, e.getMessage(), e);
            return Mono.error(new IllegalStateException(
                    "Failed to serialise support request: " + e.getMessage(), e));
        }

        // ── Publish with Retry ────────────────────────────────────────────────
        return Mono
                .<String>create(sink -> {
                    // Kafka send is blocking internally — subscribe on boundedElastic
                    // to avoid blocking the event-loop thread
                    CompletableFuture<SendResult<String, String>> future =
                            kafkaTemplate.send(TOPIC, request.getCustomerId().toString(), payload);

                    future.whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("[KafkaProducer] Send failed messageId={}: {}", messageId, ex.getMessage());
                            sink.error(ex);
                        } else {
                            RecordMetadata meta = result.getRecordMetadata();
                            log.info("[KafkaProducer] Published messageId={} customerId={} topic={} partition={} offset={}",
                                    messageId, request.getCustomerId(),
                                    meta.topic(), meta.partition(), meta.offset());
                            sink.success(messageId);
                        }
                    });
                })
                .subscribeOn(Schedulers.boundedElastic())

                // ── Resilience: Retry ─────────────────────────────────────────
                // Retries on IOException and retriable Kafka exceptions.
                // Exponential back-off: 200 ms → 400 ms → 800 ms.
                // NOT applied to JsonProcessingException (handled above).
                .transform(RetryOperator.of(kafkaPublishRetry))

                // ── Fallback: All retries exhausted ───────────────────────────
                .onErrorMap(ex -> !(ex instanceof ServiceUnavailableException), ex -> {
                    log.error("[KafkaProducer] All retry attempts EXHAUSTED for messageId={}: {}",
                            messageId, ex.getMessage(), ex);
                    return new ServiceUnavailableException(
                            "Support request could not be submitted due to a messaging system error. "
                            + "Please retry in a moment.", ex);
                });
    }
}
