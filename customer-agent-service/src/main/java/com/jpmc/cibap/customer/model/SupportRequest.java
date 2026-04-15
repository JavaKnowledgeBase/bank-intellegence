package com.jpmc.cibap.customer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Request payload for the {@code POST /api/v1/accounts/{customerId}/support-request}
 * endpoint. This object is validated on receipt, enriched server-side with
 * {@code customerId} and {@code messageId}, then published to the
 * {@code customer-support-events} Kafka topic.
 *
 * <p><strong>Validation:</strong> Bean Validation (Jakarta) is applied at the controller
 * layer via {@code @Valid}. The service layer assumes all fields are already valid when
 * this object is received.
 *
 * <p><strong>Kafka serialisation:</strong> The entire object is serialised to JSON by
 * Jackson ({@link com.fasterxml.jackson.databind.ObjectMapper}) before publishing.
 * Ensure no circular references or non-serialisable types are added.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>{@code customerId} is injected server-side from the path variable — never trust
 *       a client-supplied {@code customerId} in the body.</li>
 *   <li>{@code messageId} is set by {@link com.jpmc.cibap.customer.kafka.SupportRequestProducer}
 *       and must not be null when the Kafka consumer processes it downstream.</li>
 *   <li>{@code description} is size-capped at 2000 characters to avoid oversized Kafka
 *       messages that would exceed the default 1 MB broker limit.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Data
public class SupportRequest {

    /**
     * Populated server-side from the URL path variable {@code {customerId}}.
     * Never populated from the request body to prevent customer impersonation.
     */
    @JsonIgnore   // Excluded from deserialization; set programmatically
    private UUID customerId;

    /**
     * Idempotency key assigned by {@link com.jpmc.cibap.customer.kafka.SupportRequestProducer}
     * before the message is sent. Downstream consumers use this for deduplication.
     */
    private String messageId;

    /**
     * Category of the support request (e.g., {@code DISPUTE}, {@code LOST_CARD},
     * {@code FRAUD_REPORT}, {@code GENERAL}).
     *
     * <p>Must not be blank. Downstream routing rules in the notification service
     * depend on this value; an unexpected category will fall through to the default handler.
     */
    @NotBlank(message = "Category must not be blank")
    @Size(max = 50, message = "Category must be 50 characters or fewer")
    private String category;

    /**
     * Human-readable description of the customer's issue.
     * Capped at 2000 characters to stay within Kafka's default max message size.
     */
    @NotBlank(message = "Description must not be blank")
    @Size(max = 2000, message = "Description must be 2000 characters or fewer")
    private String description;

    /**
     * Optional reference to a specific account related to the support request.
     * May be {@code null} for general (non-account-specific) queries.
     */
    private UUID relatedAccountId;

    /**
     * Priority tier: {@code LOW}, {@code MEDIUM}, {@code HIGH}, {@code CRITICAL}.
     * Defaults to {@code MEDIUM} when not supplied by the client.
     */
    @NotNull(message = "Priority must not be null")
    private String priority = "MEDIUM";

    /** Server-set timestamp recording when this request was received. */
    private Instant receivedAt;
}
