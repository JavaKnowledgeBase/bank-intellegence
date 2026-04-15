package com.jpmc.cibap.loan.statemachine.actions;

import com.jpmc.cibap.loan.kafka.LoanDecisionProducer;
import com.jpmc.cibap.loan.model.LoanApplication;
import com.jpmc.cibap.loan.statemachine.LoanEvent;
import com.jpmc.cibap.loan.statemachine.LoanState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyAction implements Action<LoanState, LoanEvent> {

    private final LoanDecisionProducer loanDecisionProducer;

    @Override
    public void execute(StateContext<LoanState, LoanEvent> context) {
        LoanApplication app = (LoanApplication) context.getExtendedState()
                .getVariables().get("application");

        if (app.getDecision() == null) {
            if (app.getRiskScore().doubleValue() < 0.40) {
                app.setDecision("APPROVED");
                app.setDecisionReason("Meets all eligibility criteria");
            } else if (app.getRiskScore().doubleValue() < 0.70) {
                app.setDecision("MANUAL_REVIEW");
                app.setDecisionReason("Moderate risk requires underwriter review");
            } else {
                app.setDecision("DECLINED");
                app.setDecisionReason("Risk score exceeds threshold");
            }
        }

        loanDecisionProducer.publish(app);
        log.info("Loan decision published appId={} decision={}", app.getId(), app.getDecision());
        context.getStateMachine().sendEvent(LoanEvent.NOTIFICATION_SENT);
    }
}
