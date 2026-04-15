package com.jpmc.cibap.mcp.client;

import com.jpmc.cibap.mcp.config.McpProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for CTIP (CIBAP Test Intelligence Platform) on port 8091.
 * Maps to FastAPI routes in user-test-intellegence/backend/api/v1/.
 */
@Component
public class CtipClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public CtipClient(WebClient.Builder builder, McpProperties properties) {
        this.webClient = builder.baseUrl(properties.getCtipBaseUrl()).build();
    }

    // ── Test cases ────────────────────────────────────────────────────────────

    /** GET /api/v1/test-cases — list test cases with optional filters. */
    public Mono<List<Map<String, Object>>> listTestCases(
            String service, String status, int limit) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/test-cases");
                    if (service != null) b = b.queryParam("service", service);
                    if (status  != null) b = b.queryParam("status", status);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("listTestCases", ex))));
    }

    /** GET /api/v1/search?query=... — semantic search over test cases. */
    public Mono<Map<String, Object>> searchTests(String query, String serviceFilter, int topK) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/search").queryParam("q", query).queryParam("limit", topK);
                    if (serviceFilter != null) b = b.queryParam("service", serviceFilter);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("searchTests", ex)));
    }

    /** POST /api/v1/test-cases — generate a test case from a natural language scenario. */
    public Mono<Map<String, Object>> createTest(Map<String, Object> payload) {
        return webClient.post()
                .uri("/api/v1/test-cases")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(Duration.ofSeconds(30))  // generation takes longer
                .onErrorResume(ex -> Mono.just(errorMap("createTest", ex)));
    }

    // ── Test runs ─────────────────────────────────────────────────────────────

    /** POST /api/v1/runs/suite/{service} — trigger parallel suite run for a service. */
    public Mono<Map<String, Object>> runSuite(String service, Map<String, Object> payload) {
        return webClient.post()
                .uri("/api/v1/runs/suite/{service}", service)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("runSuite:" + service, ex)));
    }

    /** GET /api/v1/runs/suites/{suiteRunId} — get aggregated suite result. */
    public Mono<Map<String, Object>> getSuiteResult(String suiteRunId) {
        return webClient.get()
                .uri("/api/v1/runs/suites/{id}", suiteRunId)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("getSuiteResult:" + suiteRunId, ex)));
    }

    /** GET /api/v1/runs/suites — list all suite runs. */
    public Mono<List<Map<String, Object>>> listSuiteRuns() {
        return webClient.get()
                .uri("/api/v1/runs/suites")
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("listSuiteRuns", ex))));
    }

    // ── Deployment gate ───────────────────────────────────────────────────────

    /** GET /api/v1/gate/{service}/{commitSha} — gate decision for CI/CD pipeline. */
    public Mono<Map<String, Object>> getDeploymentGate(String service, String commit) {
        return webClient.get()
                .uri("/api/v1/gate/{service}/{commit}", service, commit)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("getDeploymentGate", ex)));
    }

    // ── Coverage ──────────────────────────────────────────────────────────────

    /** GET /api/v1/coverage?service=... — coverage analysis for a service. */
    public Mono<Map<String, Object>> getCoverage(String service) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/coverage");
                    if (service != null) b = b.queryParam("service", service);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("getCoverage:" + service, ex)));
    }

    // ── Flaky tests ───────────────────────────────────────────────────────────

    /** GET /api/v1/test-cases?status=flaky — list flaky test cases. */
    public Mono<List<Map<String, Object>>> getFlakyTests(String service) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/test-cases").queryParam("status", "flaky");
                    if (service != null) b = b.queryParam("service", service);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("getFlakyTests", ex))));
    }

    // ── Web Scout ─────────────────────────────────────────────────────────────

    /** GET /api/v1/scout/observations — scout activity log. */
    public Mono<Map<String, Object>> getScoutActivity(String date, String status) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/scout/observations");
                    if (status != null) b = b.queryParam("status", status);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("getScoutActivity", ex)));
    }

    // ── Self-improvement ──────────────────────────────────────────────────────

    /** GET /api/v1/self-improvement/proposals */
    public Mono<List<Map<String, Object>>> getSelfImprovementProposals(String status) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/self-improvement/proposals");
                    if (status != null) b = b.queryParam("status", status);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("getSelfImprovementProposals", ex))));
    }

    // ── Health ────────────────────────────────────────────────────────────────

    public Mono<Map<String, Object>> getHealth() {
        return webClient.get()
                .uri("/api/v1/health")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("ctip-health", ex)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> errorMap(String operation, Throwable ex) {
        return Map.of(
                "operation", operation,
                "success", false,
                "error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()
        );
    }
}
