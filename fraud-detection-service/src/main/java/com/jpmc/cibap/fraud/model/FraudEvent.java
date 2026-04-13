package com.jpmc.cibap.fraud.model;


import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@Data
public class FraudEvent {

    private UUID transactionId;
    private UUID accountId;
    private UUID customerId;
    private double ruleScore;
    private double mlScore;
    private double finalScore;
    private String disposition; // ALLOW | FLAG | BLOCK
    private List<String> reasons;
    private Instant evaluatedAt;
}
