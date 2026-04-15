package com.jpmc.cibap.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Customer Agent Service.
 *
 * <p>This reactive microservice (Spring WebFlux + R2DBC) is responsible for:
 * <ul>
 *   <li>Serving aggregated account summaries from PostgreSQL with Redis caching (TTL 60 s)</li>
 *   <li>Serving transaction history with date-range filtering</li>
 *   <li>Accepting customer support requests and publishing them asynchronously to Kafka</li>
 * </ul>
 *
 * <p><strong>Port:</strong> {@code 8081}
 *
 * <p><strong>Resilience patterns used:</strong>
 * <ul>
 *   <li>Circuit-Breaker on Redis and PostgreSQL calls (via Resilience4j)</li>
 *   <li>Retry with exponential back-off on Kafka publish</li>
 *   <li>Rate-Limiter on public API endpoints</li>
 *   <li>Fallback chains: Redis miss → DB, Redis down → DB, DB down → error signal</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@SpringBootApplication
@EnableScheduling   // Required for Resilience4j's sliding-window cleanup tasks
public class CustomerAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerAgentApplication.class, args);
    }
}
