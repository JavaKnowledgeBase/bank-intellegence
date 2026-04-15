package com.jpmc.cibap.loan.model;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PrescreenResponse {

    private UUID applicationId;
    private String status; // PENDING, APPROVED, DECLINED, MANUAL_REVIEW
    private String message;
    private String estimatedApr; // populated if APPROVED
}
