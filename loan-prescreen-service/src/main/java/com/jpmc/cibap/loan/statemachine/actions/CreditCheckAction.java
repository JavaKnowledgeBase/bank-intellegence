package com.jpmc.cibap.loan.statemachine.actions;

import com.jpmc.cibap.loan.model.LoanApplication;
import com.jpmc.cibap.loan.service.CreditBureauClient;
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
public class CreditCheckAction implements Action<LoanState, LoanEvent> {

    private final CreditBureauClient creditBureauClient;

    @Override
    public void execute(StateContext<LoanState, LoanEvent> context) {
        LoanApplication app = (LoanApplication) context.getExtendedState()
                .getVariables().get("application");

        try {
            int creditScore = creditBureauClient.getCreditScore(app.getCustomerId());
            app.setCreditScore(creditScore);
            log.info("Credit check appId={} score={}", app.getId(), creditScore);

            if (creditScore >= 580) {
                context.getStateMachine().sendEvent(LoanEvent.CREDIT_CHECK_PASSED);
            } else {
                app.setDecision("DECLINED");
                app.setDecisionReason("Credit score below minimum threshold: " + creditScore);
                context.getStateMachine().sendEvent(LoanEvent.CREDIT_CHECK_FAILED);
            }
        } catch (Exception ex) {
            log.error("Credit check failed appId={}", app.getId(), ex);
            app.setDecision("MANUAL_REVIEW");
            app.setDecisionReason("Credit bureau temporarily unavailable");
            context.getStateMachine().sendEvent(LoanEvent.CREDIT_CHECK_FAILED);
        }
    }
}
