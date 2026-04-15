package com.jpmc.cibap.gateway.fallback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Returns a structured degraded-service response when a circuit breaker opens.
 *
 * Spring Cloud Gateway's CircuitBreaker filter redirects internally to these
 * endpoints — the client never retries the downstream service while the circuit
 * is open.  Each route has its own fallback so the message is service-specific.
 *
 * All fallbacks return 503 Service Unavailable with a JSON body that the
 * orchestration service and front-end clients can handle gracefully.
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/agent")
    public ResponseEntity<Map<String, Object>> agentFallback() {
        log.warn("Circuit open: orchestration-service fallback triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "SERVICE_UNAVAILABLE",
                "service", "orchestration-service",
                "message", "The AI agent is temporarily unavailable. Please try again in a few moments.",
                "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/accounts")
    public ResponseEntity<Map<String, Object>> accountsFallback() {
        log.warn("Circuit open: customer-agent-service fallback triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "SERVICE_UNAVAILABLE",
                "service", "customer-agent-service",
                "message", "Account services are temporarily unavailable. Please call 1-800-935-9935.",
                "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/fraud")
    public ResponseEntity<Map<String, Object>> fraudFallback() {
        log.warn("Circuit open: fraud-detection-service fallback triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "SERVICE_UNAVAILABLE",
                "service", "fraud-detection-service",
                "message", "Fraud detection services are temporarily unavailable.",
                "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/loans")
    public ResponseEntity<Map<String, Object>> loansFallback() {
        log.warn("Circuit open: loan-prescreen-service fallback triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "SERVICE_UNAVAILABLE",
                "service", "loan-prescreen-service",
                "message", "Loan services are temporarily unavailable. Please try again shortly.",
                "timestamp", Instant.now().toString()
        ));
    }

}
