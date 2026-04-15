package com.jpmc.cibap.notification.channel;

import com.jpmc.cibap.notification.model.NotificationRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class SnsChannel {

    private final SnsClient snsClient;

    @Value("${aws.notifications.mock:true}")
    private boolean mockNotifications;

    @CircuitBreaker(name = "sns", fallbackMethod = "fallback")
    public Mono<String> sendSms(NotificationRequest request) {
        if (mockNotifications) {
            log.info("Mock SMS notification eventType={} recipient={}", request.getEventType(), request.getRecipient());
            return Mono.just("SNS_MOCK_" + request.getMessageUuid());
        }
        return Mono.fromCallable(() -> snsClient.publish(PublishRequest.builder()
                .phoneNumber(request.getRecipient())
                .message(buildSmsMessage(request))
                .build()).messageId());
    }

    public Mono<String> sendPush(NotificationRequest request) {
        if (mockNotifications) {
            log.info("Mock push notification eventType={} recipient={}", request.getEventType(), request.getRecipient());
            return Mono.just("PUSH_MOCK_" + request.getMessageUuid());
        }
        return sendSms(request);
    }

    private String buildSmsMessage(NotificationRequest request) {
        return "Chase Alert: " + request.getEventType();
    }

    public Mono<String> fallback(NotificationRequest request, Exception ex) {
        log.error("SNS circuit open - notification not sent for customerId={}", request.getCustomerId(), ex);
        return Mono.just("SNS_FALLBACK_" + request.getMessageUuid());
    }
}
