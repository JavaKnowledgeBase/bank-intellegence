package com.jpmc.cibap.mcp.client;

import com.jpmc.cibap.mcp.config.McpProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the five CIBAP downstream services.
 *
 * All calls use each service's Spring Boot Actuator endpoints plus any
 * custom REST endpoints the service exposes. When a service is unreachable
 * the error is caught and returned as a structured error map so the MCP
 * response remains well-formed.
 */
@Component
public class CibapClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder builder;
    private final McpProperties properties;

    public CibapClient(WebClient.Builder builder, McpProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /** GET /actuator/health for a single named service. */
    public Mono<Map<String, Object>> getHealth(String service) {
        String baseUrl = properties.urlFor(service);
        return webClient(baseUrl)
                .get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .map(body -> addServiceName(body, service))
                .onErrorResume(ex -> Mono.just(errorMap(service, ex)));
    }

    /**
     * Health-check all known services in parallel and return a list of results.
     * Called when the tool argument is "all".
     */
    public Mono<List<Map<String, Object>>> getHealthAll() {
        List<String> services = List.copyOf(properties.getCibapServiceUrls().keySet());
        return Flux.fromIterable(services)
                .flatMap(this::getHealth)
                .collectList();
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    /** GET /actuator/prometheus — raw Prometheus text for parsing by the handler. */
    public Mono<String> getPrometheusMetrics(String service) {
        String baseUrl = properties.urlFor(service);
        return webClient(baseUrl)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just("# ERROR: " + ex.getMessage()));
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    /**
     * GET /actuator/logfile — available when logging.file.name is configured.
     * Returns the file content; the handler is responsible for tailing N lines.
     */
    public Mono<String> getLogs(String service) {
        String baseUrl = properties.urlFor(service);
        return webClient(baseUrl)
                .get()
                .uri("/actuator/logfile")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(ex -> Mono.just(
                        "Log file not available for " + service + ": " + ex.getMessage()
                ));
    }

    // ── Service info (from /actuator/info) ────────────────────────────────────

    public Mono<Map<String, Object>> getServiceInfo(String service) {
        String baseUrl = properties.urlFor(service);
        return webClient(baseUrl)
                .get()
                .uri("/actuator/info")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .map(body -> addServiceName(body, service))
                .onErrorResume(ex -> Mono.just(Map.of(
                        "service", service,
                        "url", baseUrl,
                        "error", ex.getMessage()
                )));
    }

    // ── Platform summary ──────────────────────────────────────────────────────

    public Mono<List<Map<String, Object>>> getPlatformHealth() {
        return getHealthAll();
    }

    // ── Kafka status (custom endpoint on orchestration-service) ───────────────

    /**
     * GET /api/v1/kafka/status — exposed by the orchestration-service.
     * Falls back gracefully if not available in local dev.
     */
    public Mono<Map<String, Object>> getKafkaStatus() {
        String baseUrl = properties.urlFor("orchestration");
        return webClient(baseUrl)
                .get()
                .uri("/api/v1/kafka/status")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .map(body -> addServiceName(body, "kafka"))
                .onErrorResume(ex -> Mono.just(Map.of(
                        "note", "Kafka status endpoint not available in local dev",
                        "hint", "Start orchestration-service on port 8085 and implement GET /api/v1/kafka/status",
                        "error", ex.getMessage()
                )));
    }

    // ── Recent deployments (custom endpoint on orchestration-service) ─────────

    public Mono<Map<String, Object>> getRecentDeployments(String service, int hoursAgo) {
        String baseUrl = properties.urlFor("orchestration");
        return webClient(baseUrl)
                .get()
                .uri(u -> u.path("/api/v1/deployments")
                           .queryParam("service", service)
                           .queryParam("hoursAgo", hoursAgo)
                           .build())
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .map(body -> addServiceName(body, service))
                .onErrorResume(ex -> Mono.just(Map.of(
                        "note", "Deployment history not available in local dev",
                        "hint", "Start orchestration-service and implement GET /api/v1/deployments",
                        "error", ex.getMessage()
                )));
    }

    // ── Pod status (K8s — not available in local dev) ─────────────────────────

    public Mono<Map<String, Object>> getPodStatus(String service) {
        // K8s pod status requires kubectl/K8s API access — not available locally.
        return Mono.just(Map.of(
                "service", service,
                "note", "Pod status requires Kubernetes API access",
                "hint", "In K8s environments this queries the K8s API Server for pod details",
                "environment", "local-dev"
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WebClient webClient(String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    private Map<String, Object> addServiceName(Map<String, Object> body, String service) {
        var result = new LinkedHashMap<String, Object>(body);
        result.put("service", service);
        return result;
    }

    private Map<String, Object> errorMap(String service, Throwable ex) {
        return Map.of(
                "service", service,
                "status", "UNKNOWN",
                "error", ex.getMessage()
        );
    }
}
