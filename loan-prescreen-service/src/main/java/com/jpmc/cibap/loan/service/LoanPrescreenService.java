package com.jpmc.cibap.loan.service;

import com.jpmc.cibap.loan.model.LoanApplication;
import com.jpmc.cibap.loan.model.PrescreenRequest;
import com.jpmc.cibap.loan.model.PrescreenResponse;
import com.jpmc.cibap.loan.repository.LoanApplicationRepository;
import com.jpmc.cibap.loan.statemachine.LoanEvent;
import com.jpmc.cibap.loan.statemachine.LoanState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanPrescreenService {

    private final StateMachineFactory<LoanState, LoanEvent> stateMachineFactory;
    private final LoanApplicationRepository applicationRepository;

    public Mono<PrescreenResponse> initiate(PrescreenRequest request) {
        LoanApplication app = LoanApplication.builder()
                .id(UUID.randomUUID())
                .customerId(request.getCustomerId())
                .loanType(request.getLoanType())
                .requestedAmount(request.getAmount())
                .state(LoanState.INTAKE.name())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return applicationRepository.save(app)
                .flatMap(savedApp -> {
                    Thread.ofVirtual().start(() -> runStateMachine(savedApp));
                    return Mono.just(PrescreenResponse.builder()
                            .applicationId(savedApp.getId())
                            .status("PENDING")
                            .message("Your loan pre-screening has been initiated. You will be notified with the decision shortly.")
                            .build());
                });
    }

    private void runStateMachine(LoanApplication app) {
        StateMachine<LoanState, LoanEvent> stateMachine =
                stateMachineFactory.getStateMachine(app.getId().toString());
        stateMachine.start();
        stateMachine.getExtendedState().getVariables().put("application", app);
        stateMachine.sendEvent(LoanEvent.START_CREDIT_CHECK);
        log.info("State machine started for appId={}", app.getId());
    }
}
