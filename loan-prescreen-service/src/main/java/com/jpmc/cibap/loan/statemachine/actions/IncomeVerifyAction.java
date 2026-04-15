package com.jpmc.cibap.loan.statemachine.actions;

import com.jpmc.cibap.loan.model.LoanApplication;
import com.jpmc.cibap.loan.statemachine.LoanEvent;
import com.jpmc.cibap.loan.statemachine.LoanState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IncomeVerifyAction implements Action<LoanState, LoanEvent> {

    @Override
    public void execute(StateContext<LoanState, LoanEvent> context) {
        LoanApplication app = (LoanApplication) context.getExtendedState()
                .getVariables().get("application");

        boolean verified = mockIncomeVerification(app);
        app.setIncomeVerified(verified);
        log.info("Income verification appId={} verified={}", app.getId(), verified);

        if (verified) {
            context.getStateMachine().sendEvent(LoanEvent.INCOME_VERIFIED);
        } else {
            context.getStateMachine().sendEvent(LoanEvent.INCOME_UNVERIFIABLE);
        }
    }

    private boolean mockIncomeVerification(LoanApplication app) {
        return true;
    }
}
