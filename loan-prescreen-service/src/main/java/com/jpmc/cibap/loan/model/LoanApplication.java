package com.jpmc.cibap.loan.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@Table("loan_applications")
public class LoanApplication {

    @Id
    private UUID id;
    private UUID customerId;
    private String loanType;
    private BigDecimal requestedAmount;
    private String state;
    private Integer creditScore;
    private Boolean incomeVerified;
    private BigDecimal riskScore;
    private String decision;
    private String decisionReason;
    private Instant createdAt;
    private Instant updatedAt;
}
