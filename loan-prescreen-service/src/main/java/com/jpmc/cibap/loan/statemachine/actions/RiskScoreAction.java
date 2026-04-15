package com.jpmc.cibap.loan.statemachine.actions;

import com.jpmc.cibap.loan.model.LoanApplication;
import com.jpmc.cibap.loan.statemachine.LoanEvent;
import com.jpmc.cibap.loan.statemachine.LoanState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
public class RiskScoreAction implements Action<LoanState, LoanEvent> {

    @Override
    public void execute(StateContext<LoanState, LoanEvent> context) {
        LoanApplication app = (LoanApplication) context.getExtendedState()
                .getVariables().get("application");

        double riskScore = computeRiskScore(app);
        app.setRiskScore(BigDecimal.valueOf(riskScore));
        log.info("Risk score appId={} riskScore={}", app.getId(), riskScore);
        context.getStateMachine().sendEvent(LoanEvent.RISK_SCORE_DONE);
    }

    private double computeRiskScore(LoanApplication app) {
        double creditFactor = (850.0 - app.getCreditScore()) / 850.0;
        double amountFactor = app.getRequestedAmount()
                .divide(new BigDecimal("100000"), 4, RoundingMode.HALF_UP)
                .doubleValue();
        return Math.min(1.0, (creditFactor * 0.6) + (amountFactor * 0.4));
    }
}
