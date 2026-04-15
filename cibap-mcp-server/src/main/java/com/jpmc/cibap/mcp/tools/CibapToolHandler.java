package com.jpmc.cibap.mcp.tools;

import com.jpmc.cibap.mcp.client.CibapClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles all 8 cibap_* MCP tools by translating tool arguments into
 * CibapClient calls and shaping the results for Claude.
 */
@Component
@RequiredArgsConstructor
public class CibapToolHandler {

    private final CibapClient cibapClient;

    public Mono<Map<String, Object>> execute(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "cibap_health_check"          -> handleHealthCheck(arguments);
            case "cibap_get_metrics"           -> handleGetMetrics(arguments);
            case "cibap_get_logs"              -> handleGetLogs(arguments);
            case "cibap_get_kafka_status"      -> handleGetKafkaStatus(arguments);
            case "cibap_get_recent_deployments"-> handleGetRecentDeployments(arguments);
            case "cibap_get_pod_status"        -> handleGetPodStatus(arguments);
            case "cibap_platform_summary"      -> handlePlatformSummary(arguments);
            case "cibap_get_service_info"      -> handleGetServiceInfo(arguments);
            default -> Mono.error(new IllegalArgumentException("Unsupported CIBAP tool: " + toolName));
        };
    }

    // ── cibap_health_check ────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleHealthCheck(Map<String, Object> args) {
        Object servicesArg = args.get("services");
        List<String> requested = parseServiceList(servicesArg);

        if (requested.contains("all") || requested.isEmpty()) {
            return cibapClient.getHealthAll()
                    .map(results -> Map.of(
                            "tool", "cibap_health_check",
                            "services", results
                    ));
        }

        // Check each requested service in parallel then collect
        return reactor.core.publisher.Flux.fromIterable(requested)
                .flatMap(cibapClient::getHealth)
                .collectList()
                .map(results -> Map.of(
                        "tool", "cibap_health_check",
                        "services", results
                ));
    }

    // ── cibap_get_metrics ─────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetMetrics(Map<String, Object> args) {
        String service = String.valueOf(args.get("service"));
        int timeRangeMinutes = intArg(args, "timeRangeMinutes", 30);

        return cibapClient.getPrometheusMetrics(service)
                .map(raw -> {
                    // Extract a handful of key lines to keep the response readable
                    String summary = summarisePrometheus(raw, service);
                    return Map.of(
                            "tool", "cibap_get_metrics",
                            "service", service,
                            "timeRangeMinutes", timeRangeMinutes,
                            "summary", summary
                    );
                });
    }

    // ── cibap_get_logs ────────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetLogs(Map<String, Object> args) {
        String service = String.valueOf(args.get("service"));
        int tail = intArg(args, "tail", 100);
        String levelFilter = stringArg(args, "levelFilter", "ERROR");
        String searchTerm = stringArg(args, "searchTerm", null);

        return cibapClient.getLogs(service)
                .map(raw -> {
                    String[] lines = raw.split("\n");
                    // Apply level filter and optional search term
                    List<String> filtered = Arrays.stream(lines)
                            .filter(l -> "ALL".equalsIgnoreCase(levelFilter) || l.contains(levelFilter))
                            .filter(l -> searchTerm == null || l.contains(searchTerm))
                            .toList();
                    // Take last N lines
                    List<String> tailed = filtered.size() > tail
                            ? filtered.subList(filtered.size() - tail, filtered.size())
                            : filtered;
                    return Map.of(
                            "tool", "cibap_get_logs",
                            "service", service,
                            "levelFilter", levelFilter,
                            "lineCount", tailed.size(),
                            "lines", tailed
                    );
                });
    }

    // ── cibap_get_kafka_status ────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetKafkaStatus(Map<String, Object> args) {
        return cibapClient.getKafkaStatus()
                .map(result -> Map.of(
                        "tool", "cibap_get_kafka_status",
                        "data", result
                ));
    }

    // ── cibap_get_recent_deployments ──────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetRecentDeployments(Map<String, Object> args) {
        String service = stringArg(args, "service", "all");
        int hoursAgo = intArg(args, "hoursAgo", 24);

        return cibapClient.getRecentDeployments(service, hoursAgo)
                .map(result -> Map.of(
                        "tool", "cibap_get_recent_deployments",
                        "service", service,
                        "hoursAgo", hoursAgo,
                        "data", result
                ));
    }

    // ── cibap_get_pod_status ──────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetPodStatus(Map<String, Object> args) {
        String service = String.valueOf(args.get("service"));

        return cibapClient.getPodStatus(service)
                .map(result -> Map.of(
                        "tool", "cibap_get_pod_status",
                        "service", service,
                        "data", result
                ));
    }

    // ── cibap_platform_summary ────────────────────────────────────────────────

    private Mono<Map<String, Object>> handlePlatformSummary(Map<String, Object> args) {
        return cibapClient.getPlatformHealth()
                .map(healthResults -> Map.of(
                        "tool", "cibap_platform_summary",
                        "serviceHealth", healthResults,
                        "hint", "For full summary, also call csip_get_issues and ctip_get_suite_result"
                ));
    }

    // ── cibap_get_service_info ────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetServiceInfo(Map<String, Object> args) {
        String service = String.valueOf(args.get("service"));

        return cibapClient.getServiceInfo(service)
                .map(info -> Map.of(
                        "tool", "cibap_get_service_info",
                        "service", service,
                        "info", info,
                        "staticInfo", staticServiceInfo(service)
                ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> parseServiceList(Object arg) {
        if (arg instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (arg instanceof String s) {
            return List.of(s);
        }
        return List.of("all");
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object v = args.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    /** Extract a short readable summary from raw Prometheus text. */
    private String summarisePrometheus(String raw, String service) {
        if (raw.startsWith("# ERROR:")) return raw;
        long metricCount = Arrays.stream(raw.split("\n"))
                .filter(l -> !l.startsWith("#") && !l.isBlank())
                .count();
        // Pull out a handful of interesting lines
        String interesting = Arrays.stream(raw.split("\n"))
                .filter(l -> l.contains("jvm_memory") || l.contains("http_server_requests")
                        || l.contains("process_cpu") || l.contains("hikaricp"))
                .limit(10)
                .reduce("", (a, b) -> a + b + "\n");
        return "Service: " + service + "\nTotal metrics: " + metricCount +
               "\n\nKey metrics:\n" + (interesting.isBlank() ? "(none matched)" : interesting);
    }

    /** Static metadata about each CIBAP service. */
    private Map<String, Object> staticServiceInfo(String service) {
        return switch (service) {
            case "customer-agent" -> Map.of(
                    "description", "Customer data management — accounts, transactions, support requests",
                    "port", 8081, "tier", "P1", "tech", "Java 21 / Spring Boot 3 / WebFlux / R2DBC",
                    "kafkaTopics", List.of("customer-support-events", "transaction-events")
            );
            case "fraud-detection" -> Map.of(
                    "description", "Real-time fraud scoring — Drools rules + SageMaker ML model",
                    "port", 8082, "tier", "P0", "tech", "Java 21 / Spring Boot 3 / Drools",
                    "kafkaTopics", List.of("transaction-events", "fraud-events")
            );
            case "loan-prescreen" -> Map.of(
                    "description", "Loan pre-screening — Spring State Machine 6-state workflow",
                    "port", 8083, "tier", "P1", "tech", "Java 21 / Spring Boot 3 / Spring State Machine",
                    "kafkaTopics", List.of("loan-decision-events")
            );
            case "notification" -> Map.of(
                    "description", "Notification dispatch — Kafka consumer, idempotent SNS/SES",
                    "port", 8084, "tier", "P2", "tech", "Java 21 / Spring Boot 3 / Kafka",
                    "kafkaTopics", List.of("fraud-events", "loan-decision-events")
            );
            case "orchestration" -> Map.of(
                    "description", "LangChain4j ReAct agent — Bedrock Claude + MCP client",
                    "port", 8085, "tier", "P1", "tech", "Java 21 / Spring Boot 3 / LangChain4j",
                    "kafkaTopics", List.of()
            );
            default -> Map.of("service", service, "note", "No static info available");
        };
    }
}
