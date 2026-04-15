package com.jpmc.cibap.notification.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class NotificationRequest {
    private String messageUuid;
    private UUID customerId;
    private String eventType;
    private NotificationChannel channel;
    private String recipient;
    private String templateName;
    private Map<String, Object> templateVars;
}
