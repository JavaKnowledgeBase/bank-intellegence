package com.jpmc.cibap.customer.service;

import com.jpmc.cibap.customer.exception.AccountNotFoundException;
import com.jpmc.cibap.customer.kafka.SupportRequestProducer;
import com.jpmc.cibap.customer.model.SupportRequest;
import com.jpmc.cibap.customer.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Business logic for customer support request submission.
 *
 * <h2>Flow</h2>
 * <pre>
 * POST /support-request
 *   └─► Validate customer has ≥1 ACTIVE account (guard against orphan requests)
 *   └─► Enrich: set receivedAt timestamp
 *   └─► Publish to Kafka topic {@code customer-support-events} (with retry)
 *   └─► Return Kafka message ID to caller (HTTP 202 Accepted)
 * </pre>
 *
 * <h2>Resilience patterns applied</h2>
 *
 * <h3>1. Retry on Kafka publish</h3>
 * <p>Delegated to {@link SupportRequestProducer}, which wraps the send with the
 * {@code kafka-publish-retry} Resilience4j retry (3 attempts, exponential back-off).
 * Transient broker unavailability does not surface as a user-visible error.
 *
 * <h3>2. Guard: customer existence check</h3>
 * <p>Before publishing to Kafka, the service verifies the customer has at least one
 * ACTIVE account. This prevents orphan support requests for non-existent customers
 * from polluting the support queue and triggering downstream NullPointerExceptions in
 * the notification service.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>The existence check queries PostgreSQL. If the DB circuit-breaker is OPEN, the
 *       check will fail and the request will be rejected with 503. This is the correct
 *       behaviour — better to ask the customer to retry than to publish an unverified
 *       support request.</li>
 *   <li>There is a TOCTOU (time-of-check-time-of-use) race: between the existence check
 *       and the Kafka publish, the account could transition to CLOSED. This is an
 *       acceptable edge case for a support request — the downstream handler can deal
 *       with inactive customers.</li>
 *   <li>If Kafka publish fails after all retries, the request is lost (no dead-letter for
 *       producer-side). A durable outbox pattern (write to DB first, publish from DB)
 *       should be considered for P0 support categories.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportRequestService {

    private final AccountRepository accountRepository;
    private final SupportRequestProducer supportRequestProducer;

    /**
     * Validates, enriches, and publishes a customer support request.
     *
     * <p>The customer is verified to have at least one ACTIVE account before the
     * Kafka publish is attempted. This guards against orphan support requests that
     * would fail processing in the downstream notification service.
     *
     * @param request the support request payload; {@code customerId} must be set by the caller
     * @return a {@link Mono} emitting the Kafka {@code messageId} upon successful publish
     * @throws AccountNotFoundException    if the customer has no ACTIVE accounts
     * @throws IllegalArgumentException    if {@code request.customerId} is null (programming error)
     * @throws com.jpmc.cibap.customer.exception.ServiceUnavailableException
     *                                     if Kafka publish fails after all retries
     */
    public Mono<String> submit(SupportRequest request) {
        // ⚠ Runtime risk: customerId must be set by the controller before calling this method
        if (request.getCustomerId() == null) {
            log.error("[SupportRequestService] customerId is null — controller failed to inject it");
            return Mono.error(new IllegalArgumentException("customerId must not be null"));
        }

        log.info("[SupportRequestService] Processing support request customerId={} category={} priority={}",
                request.getCustomerId(), request.getCategory(), request.getPriority());

        return accountRepository
                .existsByCustomerIdAndStatus(request.getCustomerId(), "ACTIVE")

                // ── Guard: Verify customer exists ─────────────────────────────
                .flatMap(exists -> {
                    if (!exists) {
                        // Business validation failure — no ACTIVE account means we cannot
                        // associate this request with a valid customer in the support system
                        log.warn("[SupportRequestService] Rejected — no active account for customerId={}",
                                request.getCustomerId());
                        return Mono.<String>error(
                                new AccountNotFoundException(
                                        "No active account found for customer: " + request.getCustomerId()));
                    }

                    // ── Enrich request ────────────────────────────────────────
                    // Server-side timestamp — never trust client-supplied timestamps
                    request.setReceivedAt(Instant.now());

                    log.debug("[SupportRequestService] Enriched request, publishing to Kafka customerId={}",
                            request.getCustomerId());

                    // ── Publish to Kafka (with retry via SupportRequestProducer) ──
                    return supportRequestProducer.publish(request);
                })

                .doOnSuccess(messageId ->
                        log.info("[SupportRequestService] Support request accepted messageId={} customerId={}",
                                messageId, request.getCustomerId()))

                .doOnError(ex ->
                        log.error("[SupportRequestService] Failed to submit support request customerId={}: {}",
                                request.getCustomerId(), ex.getMessage(), ex));
    }
}
