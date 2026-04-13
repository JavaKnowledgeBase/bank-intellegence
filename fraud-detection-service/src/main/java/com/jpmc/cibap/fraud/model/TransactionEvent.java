package com.jpmc.cibap.fraud.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class TransactionEvent {

    private UUID transactionId;
    private UUID accountId;
    private UUID customerId;
    private BigDecimal amount;
    private String merchant;
    private String merchantCategoryCode;
    private boolean international;
    private boolean travelNotice;
    private boolean velocityFlag; // pre-computed by enricher
    private int hourOfDay;
    private Instant timestamp;
}
