package com.jpmc.cibap.orchestration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient customerAgentClient(
            WebClient.Builder builder,
            @Value("${services.customer-agent.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient fraudDetectionClient(
            WebClient.Builder builder,
            @Value("${services.fraud-detection.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient loanPrescreenClient(
            WebClient.Builder builder,
            @Value("${services.loan-prescreen.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}