package com.jpmc.cibap.notification.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "fraud:dedup:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public Mono<Boolean> isNew(String messageUuid) {
        String key = KEY_PREFIX + messageUuid;
        return redisTemplate.opsForValue()
                .setIfAbsent(key, "processed", DEDUP_TTL)
                .map(Boolean.TRUE::equals)
                .doOnNext(isNew -> {
                    if (!isNew) {
                        log.warn("Duplicate message detected messageUuid={} - skipping", messageUuid);
                    }
                });
    }
}
