package com.jpmc.cibap.notification.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class LoanDecisionPayload {
    private UUID applicationId;
    private UUID customerId;
    private String loanType;
    private BigDecimal requestedAmount;
    private String decision;
    private String decisionReason;
}
