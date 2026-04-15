package com.jpmc.cibap.loan.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.loan.model.LoanApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoanDecisionProducer {

    private static final String TOPIC = "loan-decision-events";
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    public void publish(LoanApplication app) {
        try {
            String payload = objectMapper.writeValueAsString(app);
            kafkaTemplate.send(TOPIC, app.getCustomerId().toString(), payload);
            log.info("Loan decision event published appId={} decision={}",
                    app.getId(), app.getDecision());
        } catch (Exception e) {
            log.error("Failed to publish loan decision appId={}", app.getId(), e);
        }
    }
}
