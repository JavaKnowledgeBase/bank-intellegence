package com.jpmc.cibap.loan.statemachine;

public enum LoanState {

    INTAKE,
    CREDIT_CHECK,
    INCOME_VERIFY,
    RISK_SCORE,
    DECISION,
    NOTIFY,
    COMPLETED,
    FAILED
}
