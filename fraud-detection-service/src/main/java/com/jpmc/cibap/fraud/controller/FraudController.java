package com.jpmc.cibap.fraud.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;


@Slf4j
@RestController
@RequestMapping("/api/v1/fraud")
@RequiredArgsConstructor
public class FraudController {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    /**
     * Looks up the persisted fraud score for a transaction.
     * Fraud events are stored in Redis (key: fraud:score:{txId}, TTL=24h)
     * by the FraudEventProducer after each decision.
     */
    @GetMapping("/transactions/{txId}/score")
    public Mono<String> getTransactionFraudScore(@PathVariable UUID txId) {
        String key = "fraud:score:" + txId;
        return redisTemplate.opsForValue().get(key)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Fraud score not found for transaction: " + txId)));
    }
}
