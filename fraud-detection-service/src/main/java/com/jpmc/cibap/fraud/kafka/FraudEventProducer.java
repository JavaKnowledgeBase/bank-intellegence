package com.jpmc.cibap.fraud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.fraud.model.FraudEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FraudEventProducer {

    private static final String TOPIC = "fraud-events";
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(FraudEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            // Partition by accountId for ordered processing per account
            kafkaTemplate.send(TOPIC, event.getAccountId().toString(), payload)
                    .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish fraud event txId={}",
                            event.getTransactionId(), ex);
                } else {
                    log.debug("Fraud event published txId={} disposition={}",
                            event.getTransactionId(), event.getDisposition());
                }
            });
        } catch (Exception e) {
            log.error("Serialization error for fraud event txId={}", event.getTransactionId(), e);
        }
    }

}
