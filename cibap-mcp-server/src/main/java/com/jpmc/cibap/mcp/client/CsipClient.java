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
 * HTTP client for CSIP (CIBAP Support Intelligence Platform) on port 8092.
 * Maps to FastAPI routes in support-intellegence/backend/api/v1/.
 */
@Component
public class CsipClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public CsipClient(WebClient.Builder builder, McpProperties properties) {
        this.webClient = builder.baseUrl(properties.getCsipBaseUrl()).build();
    }

    // ── Apps ──────────────────────────────────────────────────────────────────

    public Mono<List<Map<String, Object>>> listMonitoredApps(
            String teamId, String tier, String status) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/apps");
                    if (teamId != null) b = b.queryParam("team_id", teamId);
                    if (tier   != null) b = b.queryParam("tier", tier);
                    if (status != null) b = b.queryParam("status", status);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("listMonitoredApps", ex))));
    }

    public Mono<Map<String, Object>> registerApp(Map<String, Object> payload) {
        return webClient.post()
                .uri("/api/v1/apps")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("registerApp", ex)));
    }

    // ── Issues ────────────────────────────────────────────────────────────────

    public Mono<List<Map<String, Object>>> getIssues(
            String status, String category, String severity, String service, int limit) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/issues").queryParam("limit", limit);
                    if (status   != null) b = b.queryParam("status", status);
                    if (category != null) b = b.queryParam("category", category);
                    if (severity != null) b = b.queryParam("severity", severity);
                    if (service  != null) b = b.queryParam("app_id", service);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("getIssues", ex))));
    }

    public Mono<Map<String, Object>> getIssueDetail(String issueId) {
        return webClient.get()
                .uri("/api/v1/issues/{id}", issueId)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("getIssueDetail:" + issueId, ex)));
    }

    public Mono<Map<String, Object>> escalateIssue(String issueId, String reason) {
        String note = "Fix retry requested via MCP" +
                (reason != null ? ": " + reason : "");
        return webClient.post()
                .uri("/api/v1/issues/{id}/escalate", issueId)
                .bodyValue(Map.of("reason", note))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("escalateIssue:" + issueId, ex)));
    }

    // ── Audit ─────────────────────────────────────────────────────────────────

    public Mono<List<Map<String, Object>>> getAuditLog(
            String eventType, String service, int limit) {
        return webClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/audit").queryParam("limit", limit);
                    if (eventType != null) b = b.queryParam("event_type", eventType);
                    if (service   != null) b = b.queryParam("app_id", service);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("getAuditLog", ex))));
    }

    // ── Fix pipeline ──────────────────────────────────────────────────────────

    /** Filter issues by fix-in-progress statuses as a proxy for pipeline status. */
    public Mono<List<Map<String, Object>>> getFixPipelineStatus(String issueId) {
        return webClient.get()
                .uri(u -> u.path("/api/v1/issues")
                           .queryParam("status", "fix_building")
                           .queryParam("limit", 20)
                           .build())
                .retrieve()
                .bodyToMono(LIST_MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(List.of(errorMap("getFixPipelineStatus", ex))));
    }

    public Mono<Map<String, Object>> triggerFixRetry(String issueId, String additionalContext) {
        String note = "Fix retry requested via MCP" +
                (additionalContext != null ? ": " + additionalContext : "");
        return webClient.post()
                .uri("/api/v1/issues/{id}/escalate", issueId)
                .bodyValue(Map.of("reason", note))
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("triggerFixRetry:" + issueId, ex)));
    }

    // ── Self-improvement (not yet implemented in CSIP) ────────────────────────

    public Mono<Map<String, Object>> getSelfImprovementProposals(String status) {
        return Mono.just(Map.of(
                "note", "Self-improvement proposals endpoint not yet implemented in CSIP",
                "hint", "Add GET /api/v1/self-improvement/proposals to the CSIP FastAPI backend",
                "requested_status", status != null ? status : "pending"
        ));
    }

    public Mono<Map<String, Object>> triggerSelfImprovement() {
        return Mono.just(Map.of(
                "note", "Self-improvement trigger not yet implemented in CSIP",
                "hint", "Add POST /api/v1/self-improvement/trigger to the CSIP FastAPI backend"
        ));
    }

    // ── System health ─────────────────────────────────────────────────────────

    public Mono<Map<String, Object>> getSystemHealth() {
        return webClient.get()
                .uri("/api/v1/system/health")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(TIMEOUT)
                .onErrorResume(ex -> Mono.just(errorMap("csip-system-health", ex)));
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
