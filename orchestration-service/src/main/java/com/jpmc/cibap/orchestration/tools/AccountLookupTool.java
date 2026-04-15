package com.jpmc.cibap.orchestration.tools;


import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AccountLookupTool {

    @Qualifier("customerAgentClient")
    private final WebClient customerAgentClient;

    @Tool("Retrieves a customer's account summary including balances and status. " +
            "Use when the customer asks about their balance, account info, or account status.")
    public String getAccountSummary(

            @P("The unique customer identifier UUID") String customerId) {
        return customerAgentClient.get()
                .uri("/api/v1/accounts/{id}/summary", customerId)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just(
                        "{\"error\": \"Account lookup temporarily unavailable\"}"))
                .block();
    }

    public String getRecentTransactions(
            @P("The customer ID UUID") String customerId,
            @P("Start date in ISO-8601 format, e.g. 2025-01-01") String from,
            @P("End date in ISO-8601 format, e.g. 2025-01-31") String to) {

        return customerAgentClient.get()
                .uri(uriBuilder -> uriBuilder
                .path("/api/v1/accounts/{id}/transactions")
                .queryParam("from", from)
                .queryParam("to", to)
                .build(customerId))
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("[]"))
                .block();

    }

}
