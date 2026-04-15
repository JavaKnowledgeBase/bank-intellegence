package com.jpmc.cibap.notification.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@Table("notification_log")
public class NotificationLog {
    @Id
    private UUID id;
    private String messageUuid;
    private UUID customerId;
    private String eventType;
    private String channel;
    private String recipient;
    private String status;
    private String providerRef;
    private Instant createdAt;
}
