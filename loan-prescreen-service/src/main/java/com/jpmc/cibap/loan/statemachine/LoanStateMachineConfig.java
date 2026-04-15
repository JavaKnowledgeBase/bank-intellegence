package com.jpmc.cibap.loan.statemachine;

import com.jpmc.cibap.loan.statemachine.actions.CreditCheckAction;
import com.jpmc.cibap.loan.statemachine.actions.IncomeVerifyAction;
import com.jpmc.cibap.loan.statemachine.actions.NotifyAction;
import com.jpmc.cibap.loan.statemachine.actions.RiskScoreAction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
@RequiredArgsConstructor
public class LoanStateMachineConfig extends StateMachineConfigurerAdapter<LoanState, LoanEvent> {

    private final CreditCheckAction creditCheckAction;
    private final IncomeVerifyAction incomeVerifyAction;
    private final RiskScoreAction riskScoreAction;
    private final NotifyAction notifyAction;

    @Override
    public void configure(StateMachineStateConfigurer<LoanState, LoanEvent> states) throws Exception {
        states
                .withStates()
                .initial(LoanState.INTAKE)
                .states(EnumSet.allOf(LoanState.class))
                .end(LoanState.COMPLETED)
                .end(LoanState.FAILED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<LoanState, LoanEvent> transitions) throws Exception {
        transitions
                .withExternal()
                    .source(LoanState.INTAKE)
                    .target(LoanState.CREDIT_CHECK)
                    .event(LoanEvent.START_CREDIT_CHECK)
                    .action(creditCheckAction)
                .and()
                .withExternal()
                    .source(LoanState.CREDIT_CHECK)
                    .target(LoanState.INCOME_VERIFY)
                    .event(LoanEvent.CREDIT_CHECK_PASSED)
                    .action(incomeVerifyAction)
                .and()
                .withExternal()
                    .source(LoanState.CREDIT_CHECK)
                    .target(LoanState.FAILED)
                    .event(LoanEvent.CREDIT_CHECK_FAILED)
                .and()
                .withExternal()
                    .source(LoanState.INCOME_VERIFY)
                    .target(LoanState.RISK_SCORE)
                    .event(LoanEvent.INCOME_VERIFIED)
                    .action(riskScoreAction)
                .and()
                .withExternal()
                    .source(LoanState.INCOME_VERIFY)
                    .target(LoanState.DECISION)
                    .event(LoanEvent.INCOME_UNVERIFIABLE)
                    .action(riskScoreAction)
                .and()
                .withExternal()
                    .source(LoanState.RISK_SCORE)
                    .target(LoanState.DECISION)
                    .event(LoanEvent.RISK_SCORE_DONE)
                .and()
                .withExternal()
                    .source(LoanState.DECISION)
                    .target(LoanState.NOTIFY)
                    .event(LoanEvent.DECISION_MADE)
                    .action(notifyAction)
                .and()
                .withExternal()
                    .source(LoanState.NOTIFY)
                    .target(LoanState.COMPLETED)
                    .event(LoanEvent.NOTIFICATION_SENT);
    }
}
