package com.jpmc.cibap.loan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreditBureauClient {

    private final WebClient creditBureauWebClient;

    @Value("${credit-bureau.mock:true}")
    private boolean mockMode;
    /**
     * In production: calls Experian/Equifax/TransUnion APIs.
     * In local/staging: returns a mocked score.
     */
    @CircuitBreaker(name = "credit-bureau", fallbackMethod = "fallbackScore")
    public int getCreditScore(UUID customerId) {
        if (mockMode) {
            log.info("Mock mode: returning fixed credit score for customerId={}", customerId);
            return 720; // Good credit — mock
        }
        return creditBureauWebClient.get()
                .uri("/credit-score/{customerId}", customerId)
                .retrieve()
                .bodyToMono(Integer.class)
                .block();
    }
    public int fallbackScore(UUID customerId, Exception ex) {
        log.warn("Credit bureau circuit open for customerId={} — defaulting to 0", customerId);
        return 0; // Will trigger MANUAL_REVIEW in CreditCheckAction
    }
}
