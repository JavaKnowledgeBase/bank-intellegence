package com.jpmc.cibap.notification.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class FraudEventPayload {
    private UUID transactionId;
    private UUID accountId;
    private UUID customerId;
    private BigDecimal amount;
    private String merchant;
    private double finalScore;
    private String disposition;
    private Instant evaluatedAt;
}
