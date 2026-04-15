package com.jpmc.cibap.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpmc.cibap.notification.channel.NotificationDispatcher;
import com.jpmc.cibap.notification.idempotency.IdempotencyService;
import com.jpmc.cibap.notification.model.FraudEventPayload;
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
public class FraudEventConsumer {

    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final NotificationDispatcher dispatcher;

    @KafkaListener(topics = "fraud-events", groupId = "notification-fraud-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeFraudEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            FraudEventPayload payload = objectMapper.readValue(record.value(), FraudEventPayload.class);
            if ("ALLOW".equalsIgnoreCase(payload.getDisposition())) {
                ack.acknowledge();
                return;
            }

            String messageUuid = payload.getTransactionId() + "-" + payload.getDisposition();
            idempotencyService.isNew(messageUuid)
                    .filter(Boolean::booleanValue)
                    .flatMap(isNew -> dispatcher.dispatch(NotificationRequest.builder()
                            .messageUuid(messageUuid)
                            .customerId(payload.getCustomerId())
                            .eventType("FRAUD_" + payload.getDisposition())
                            .channel(NotificationChannel.SMS)
                            .recipient("+15550000000")
                            .templateName("fraud-alert")
                            .templateVars(Map.of(
                                    "amount", payload.getAmount(),
                                    "merchant", payload.getMerchant(),
                                    "disposition", payload.getDisposition()))
                            .build()))
                    .doFinally(signal -> ack.acknowledge())
                    .subscribe();
        } catch (Exception e) {
            log.error("Error processing fraud event from Kafka", e);
        }
    }
}
