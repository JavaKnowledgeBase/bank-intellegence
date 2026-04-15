package com.jpmc.cibap.notification.channel;

import com.jpmc.cibap.notification.model.NotificationLog;
import com.jpmc.cibap.notification.model.NotificationRequest;
import com.jpmc.cibap.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final SnsChannel snsChannel;
    private final SesChannel sesChannel;
    private final PushChannel pushChannel;
    private final NotificationLogRepository logRepository;

    public Mono<Void> dispatch(NotificationRequest request) {
        Mono<String> send = switch (request.getChannel()) {
            case SMS -> snsChannel.sendSms(request);
            case EMAIL -> sesChannel.sendEmail(request);
            case PUSH -> pushChannel.sendPush(request);
        };

        return send.flatMap(providerRef -> logRepository.save(NotificationLog.builder()
                        .id(UUID.randomUUID())
                        .messageUuid(request.getMessageUuid())
                        .customerId(request.getCustomerId())
                        .eventType(request.getEventType())
                        .channel(request.getChannel().name())
                        .recipient(request.getRecipient())
                        .status("SENT")
                        .providerRef(providerRef)
                        .createdAt(Instant.now())
                        .build()))
                .doOnSuccess(logEntry -> log.info("Notification sent channel={} eventType={} customerId={}",
                        request.getChannel(), request.getEventType(), request.getCustomerId()))
                .then();
    }
}
