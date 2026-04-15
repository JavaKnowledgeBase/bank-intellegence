package com.jpmc.cibap.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.notification.channel.NotificationDispatcher;
import com.jpmc.cibap.notification.idempotency.IdempotencyService;
import com.jpmc.cibap.notification.model.LoanDecisionPayload;
import com.jpmc.cibap.notification.model.NotificationChannel;
import com.jpmc.cibap.notification.model.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoanDecisionConsumer {

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final NotificationDispatcher dispatcher;

    @KafkaListener(topics = "loan-decision-events", groupId = "notification-loan-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeLoanDecision(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            LoanDecisionPayload payload = objectMapper.readValue(record.value(), LoanDecisionPayload.class);
            String messageUuid = payload.getApplicationId() + "-loan-decision";
            String templateName = switch (payload.getDecision()) {
                case "APPROVED" -> "loan-approved";
                case "DECLINED" -> "loan-declined";
                default -> "loan-review";
            };

            idempotencyService.isNew(messageUuid)
                    .filter(Boolean::booleanValue)
                    .flatMap(isNew -> dispatcher.dispatch(NotificationRequest.builder()
                            .messageUuid(messageUuid)
                            .customerId(payload.getCustomerId())
                            .eventType("LOAN_" + payload.getDecision())
                            .channel(NotificationChannel.EMAIL)
                            .recipient("customer@example.com")
                            .templateName(templateName)
                            .templateVars(Map.of(
                                    "loanType", payload.getLoanType(),
                                    "amount", payload.getRequestedAmount(),
                                    "decision", payload.getDecision(),
                                    "reason", payload.getDecisionReason()))
                            .build()))
                    .doFinally(signal -> ack.acknowledge())
                    .subscribe();
        } catch (Exception e) {
            log.error("Error processing loan decision event from Kafka", e);
        }
    }
}
