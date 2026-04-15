package com.jpmc.cibap.loan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class CreditBureauConfig {

    @Bean
    public WebClient creditBureauWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${credit-bureau.base-url}") String creditBureauBaseUrl) {
        return webClientBuilder
                .baseUrl(creditBureauBaseUrl)
                .build();
    }
}
