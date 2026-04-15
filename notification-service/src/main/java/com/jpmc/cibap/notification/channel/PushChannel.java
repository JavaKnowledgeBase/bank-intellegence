package com.jpmc.cibap.notification.channel;

import com.jpmc.cibap.notification.model.NotificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class PushChannel {

    private final SnsChannel snsChannel;

    public Mono<String> sendPush(NotificationRequest request) {
        return snsChannel.sendPush(request);
    }
}
