package com.jpmc.cibap.loan.controller;

import com.jpmc.cibap.loan.model.PrescreenRequest;
import com.jpmc.cibap.loan.model.PrescreenResponse;
import com.jpmc.cibap.loan.service.LoanPrescreenService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@Validated
@RequestMapping("/api/v1/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanPrescreenService loanPrescreenService;

    @PostMapping("/prescreen")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Timed(value = "loan.prescreen.initiation", description = "Loan pre-screen initiation latency")
    @PreAuthorize("hasAuthority('SCOPE_loans:write')")
    public Mono<PrescreenResponse> initiatePrescreen(
            @Valid @RequestBody PrescreenRequest request) {
        return loanPrescreenService.initiate(request);
    }
}
