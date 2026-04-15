package com.jpmc.cibap.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class FraudCheckTool {

    private final WebClient fraudDetectionClient;

    @Tool("Checks whether a specific transaction has been flagged for fraud. " +
            "Returns fraud score (0.0-1.0) and disposition: ALLOW, FLAG, or BLOCK.")
    public String checkTransactionFraudStatus(
            @P("The transaction UUID to inspect") String transactionId) {
        return fraudDetectionClient.get()
                .uri("/api/v1/fraud/transactions/{txId}/score", transactionId)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
