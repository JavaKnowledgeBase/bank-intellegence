package com.jpmc.cibap.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate-limiter key resolution.
 *
 * The RequestRateLimiter gateway filter uses this bean (referenced by SpEL
 * expression in application.yml) to derive the bucket key for each request.
 *
 * Strategy:
 *  1. If the request carries a JWT, use the subject claim (customerId) so
 *     every customer gets an independent quota.
 *  2. Fall back to the client IP address for unauthenticated paths (e.g.
 *     health checks won't hit rate limiting — they are permitted without JWT).
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver jwtSubjectKeyResolver() {
        return exchange ->
                exchange.getPrincipal()
                        .map(principal -> principal.getName())
                        .switchIfEmpty(Mono.fromSupplier(() -> {
                            var addr = exchange.getRequest().getRemoteAddress();
                            return addr != null
                                    ? addr.getAddress().getHostAddress()
                                    : "anonymous";
                        }));
    }

}
