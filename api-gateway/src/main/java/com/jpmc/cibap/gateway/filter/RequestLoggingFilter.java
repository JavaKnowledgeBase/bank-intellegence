package com.jpmc.cibap.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Structured access log for every request routed through the gateway.
 *
 * Emits two log lines per request:
 *   gateway_request  — method, path, correlation-id
 *   gateway_response — HTTP status, path, duration, correlation-id
 *
 * In production the logback JSON encoder picks these up so they become
 * searchable fields in CloudWatch Logs / Grafana Loki.
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startMs = System.currentTimeMillis();
        String correlationId = exchange.getRequest()
                .getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);

        log.info("gateway_request method={} path={} correlationId={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                correlationId);

        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    long durationMs = System.currentTimeMillis() - startMs;
                    log.info("gateway_response status={} path={} durationMs={} correlationId={}",
                            exchange.getResponse().getStatusCode(),
                            exchange.getRequest().getPath().value(),
                            durationMs,
                            correlationId);
                }));
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
