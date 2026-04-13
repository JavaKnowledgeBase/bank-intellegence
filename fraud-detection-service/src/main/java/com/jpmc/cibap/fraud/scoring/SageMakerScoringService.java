package com.jpmc.cibap.fraud.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.fraud.model.TransactionEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SageMakerScoringService {

    private final SageMakerRuntimeClient sageMakerClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sagemaker.endpoint-name:cibap-fraud-model-v2}")
    private String endpointName;

    /**
     * Calls the SageMaker inference endpoint synchronously.
     * Circuit breaker returns 0.5 (neutral score) on fallback.
     */
    @Timed(value = "fraud.sagemaker.latency", description = "Sagemaker inference latency")
    @CircuitBreaker(name = "sagemaker", fallbackMethod = "fallbackScore")
    public double score(TransactionEvent tx){
        try{
            Map<String, Object> features = Map.of(
                    "amount", tx.getAmount().doubleValue(),
                    "hour_of_day", tx.getHourOfDay(),
                    "is_international", tx.isInternational() ? 1 : 0,
                    "velocity_flag", tx.isVelocityFlag() ? 1 : 0,
                    "mcc", tx.getMerchantCategoryCode()
            );

            String payload = objectMapper.writeValueAsString(features);
            InvokeEndpointRequest request = InvokeEndpointRequest.builder()
                    .endpointName(endpointName)
                    .contentType("application/json")
                    .body(SdkBytes.fromUtf8String(payload))
                    .build();

            InvokeEndpointResponse response = sageMakerClient.invokeEndpoint(request);
            String responseBody = response.body().asUtf8String();
            Map<?, ?> result = objectMapper.readValue(responseBody, Map.class);
            double mlScore = ((Number) result.get("fraud_score")).doubleValue();
            log.debug("SageMaker score={} txId={}", mlScore, tx.getTransactionId());
            return mlScore;

        } catch (Exception e) {
            log.error("SageMaker scoring error txId={}", tx.getTransactionId(), e);
            throw new RuntimeException("SageMaker scoring failed", e);
        }

    }

    public double fallbackScore(TransactionEvent tx, Exception ex) {
        log.warn("SageMaker circuit open - returning neutral score for txId={}", tx.getTransactionId());
        return 0.5;
    }
}
