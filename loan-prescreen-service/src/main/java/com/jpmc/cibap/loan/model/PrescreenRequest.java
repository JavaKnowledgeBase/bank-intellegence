package com.jpmc.cibap.loan.model;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PrescreenRequest {

    @NotNull
    private UUID customerId;
    @NotNull
    @DecimalMin(value = "1000.00", message = "Minimum loan amount is $1,000")
    @DecimalMax(value = "500000.00", message = "Maximum loan amount is $500,000")
    private BigDecimal amount;
    @NotBlank
    @Pattern(regexp = "PERSONAL|AUTO|HOME_EQUITY|CREDIT_LINE",
            message = "loanType must be one of: PERSONAL, AUTO, HOME_EQUITY, CREDIT_LINE")
    private String loanType;
}
