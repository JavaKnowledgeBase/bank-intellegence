package com.jpmc.cibap.orchestration.tools;


import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class LoanEligibilityTool {

    private final WebClient loanPrescreenClient;

    @Tool("Initiates a loan pre-screening check for a customer. " +
            "Returns eligibility status and estimated terms. " +
            "Use when the customer explicitly asks about a loan or credit line.")
    public String initiatePreScreen(
            @P("Customer UUID") String customerId,
            @P("Requested loan amount in USD, numeric only") double requestedAmount,
            @P("Loan type: PERSONAL, AUTO, HOME_EQUITY, or CREDIT_LINE") String loanType) {
        String body = String.format(
                "{\"customerId\":\"%s\",\"amount\":%.2f,\"loanType\":\"%s\"}",
                customerId, requestedAmount, loanType);
        return loanPrescreenClient.post()
                .uri("/api/v1/loans/prescreen")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
