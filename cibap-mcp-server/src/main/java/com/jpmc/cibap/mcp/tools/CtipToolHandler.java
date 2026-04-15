package com.jpmc.cibap.mcp.tools;

import com.jpmc.cibap.mcp.client.CtipClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles all 12 ctip_* MCP tools by delegating to CtipClient.
 */
@Component
@RequiredArgsConstructor
public class CtipToolHandler {

    private final CtipClient ctipClient;

    public Mono<Map<String, Object>> execute(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "ctip_list_test_cases"              -> handleListTestCases(arguments);
            case "ctip_search_tests"                 -> handleSearchTests(arguments);
            case "ctip_create_test"                  -> handleCreateTest(arguments);
            case "ctip_run_test"                     -> handleRunTest(arguments);
            case "ctip_run_suite"                    -> handleRunSuite(arguments);
            case "ctip_get_run_result"               -> handleGetRunResult(arguments);
            case "ctip_get_suite_result"             -> handleGetSuiteResult(arguments);
            case "ctip_get_deployment_gate"          -> handleGetDeploymentGate(arguments);
            case "ctip_get_coverage"                 -> handleGetCoverage(arguments);
            case "ctip_get_flaky_tests"              -> handleGetFlakyTests(arguments);
            case "ctip_get_scout_activity"           -> handleGetScoutActivity(arguments);
            case "ctip_get_self_improvement_proposals" -> handleGetSelfImprovementProposals(arguments);
            default -> Mono.error(new IllegalArgumentException("Unsupported CTIP tool: " + toolName));
        };
    }

    // ── ctip_list_test_cases ──────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleListTestCases(Map<String, Object> args) {
        String service = stringArg(args, "service", null);
        String status  = stringArg(args, "status", "active");
        int    limit   = intArg(args, "limit", 20);

        if ("all".equalsIgnoreCase(status)) status = null;

        return ctipClient.listTestCases(service, status, limit)
                .map(cases -> Map.of(
                        "tool", "ctip_list_test_cases",
                        "count", cases.size(),
                        "testCases", cases
                ));
    }

    // ── ctip_search_tests ─────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleSearchTests(Map<String, Object> args) {
        String query         = String.valueOf(args.get("query"));
        String serviceFilter = stringArg(args, "serviceFilter", null);
        int    topK          = intArg(args, "topK", 10);

        return ctipClient.searchTests(query, serviceFilter, topK)
                .map(result -> Map.of(
                        "tool", "ctip_search_tests",
                        "query", query,
                        "result", result
                ));
    }

    // ── ctip_create_test ──────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleCreateTest(Map<String, Object> args) {
        String scenario       = String.valueOf(args.get("scenario"));
        String targetService  = String.valueOf(args.get("targetService"));
        String priority       = stringArg(args, "priority", "medium");
        String linkedIssueId  = stringArg(args, "linkedIssueId", null);

        Object tagsArg = args.get("tags");
        List<String> tags = tagsArg instanceof List<?> l
                ? l.stream().map(Object::toString).toList()
                : List.of();

        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", "MCP-generated: " + scenario.substring(0, Math.min(60, scenario.length())));
        payload.put("scenario_description", scenario);
        payload.put("target_service", targetService);
        payload.put("priority", priority);
        payload.put("tags", tags);
        payload.put("source", "mcp_tool");
        if (linkedIssueId != null) payload.put("linked_issue_id", linkedIssueId);

        return ctipClient.createTest(payload)
                .map(result -> Map.of(
                        "tool", "ctip_create_test",
                        "scenario", scenario,
                        "targetService", targetService,
                        "result", result
                ));
    }

    // ── ctip_run_test ─────────────────────────────────────────────────────────

    /**
     * Run a single test by triggering a 1-test suite for its service.
     * CTIP's execution model is suite-based; individual run endpoint is /runs/suite/{service}.
     */
    private Mono<Map<String, Object>> handleRunTest(Map<String, Object> args) {
        String testCaseId  = String.valueOf(args.get("testCaseId"));
        String environment = stringArg(args, "environment", "staging");

        var payload = new LinkedHashMap<String, Object>();
        payload.put("test_case_ids", List.of(testCaseId));
        payload.put("environment", environment);
        payload.put("trigger", "mcp_tool");

        // Use the generic suite runner; derive service from test case via payload
        return ctipClient.runSuite("default", payload)
                .map(result -> Map.of(
                        "tool", "ctip_run_test",
                        "testCaseId", testCaseId,
                        "environment", environment,
                        "result", result
                ));
    }

    // ── ctip_run_suite ────────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleRunSuite(Map<String, Object> args) {
        String service        = String.valueOf(args.get("service"));
        String priorityFilter = stringArg(args, "priorityFilter", "all");

        Object tagsArg = args.get("tags");
        List<String> tags = tagsArg instanceof List<?> l
                ? l.stream().map(Object::toString).toList()
                : List.of();

        var payload = new LinkedHashMap<String, Object>();
        payload.put("trigger", "mcp_tool");
        payload.put("priority_filter", priorityFilter);
        if (!tags.isEmpty()) payload.put("tags", tags);

        return ctipClient.runSuite(service, payload)
                .map(result -> Map.of(
                        "tool", "ctip_run_suite",
                        "service", service,
                        "result", result
                ));
    }

    // ── ctip_get_run_result ───────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetRunResult(Map<String, Object> args) {
        String runId = String.valueOf(args.get("runId"));

        // Single runs are exposed through the suite listing
        return ctipClient.listSuiteRuns()
                .map(runs -> {
                    var match = runs.stream()
                            .filter(r -> runId.equals(r.get("id")))
                            .findFirst()
                            .orElse(Map.of("note", "Run " + runId + " not found"));
                    return Map.of(
                            "tool", "ctip_get_run_result",
                            "runId", runId,
                            "result", match
                    );
                });
    }

    // ── ctip_get_suite_result ─────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetSuiteResult(Map<String, Object> args) {
        String suiteRunId = String.valueOf(args.get("suiteRunId"));

        return ctipClient.getSuiteResult(suiteRunId)
                .map(result -> Map.of(
                        "tool", "ctip_get_suite_result",
                        "suiteRunId", suiteRunId,
                        "result", result
                ));
    }

    // ── ctip_get_deployment_gate ──────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetDeploymentGate(Map<String, Object> args) {
        String service = String.valueOf(args.get("service"));
        String commit  = String.valueOf(args.get("commit"));

        return ctipClient.getDeploymentGate(service, commit)
                .map(result -> Map.of(
                        "tool", "ctip_get_deployment_gate",
                        "service", service,
                        "commit", commit,
                        "gate", result
                ));
    }

    // ── ctip_get_coverage ─────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetCoverage(Map<String, Object> args) {
        String service = String.valueOf(args.get("service"));

        return ctipClient.getCoverage(service)
                .map(result -> Map.of(
                        "tool", "ctip_get_coverage",
                        "service", service,
                        "coverage", result
                ));
    }

    // ── ctip_get_flaky_tests ──────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetFlakyTests(Map<String, Object> args) {
        String service = stringArg(args, "service", null);

        return ctipClient.getFlakyTests(service)
                .map(cases -> Map.of(
                        "tool", "ctip_get_flaky_tests",
                        "count", cases.size(),
                        "flakyTests", cases
                ));
    }

    // ── ctip_get_scout_activity ───────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetScoutActivity(Map<String, Object> args) {
        String date   = stringArg(args, "date", null);
        String status = stringArg(args, "status", "all");

        if ("all".equalsIgnoreCase(status)) status = null;

        return ctipClient.getScoutActivity(date, status)
                .map(result -> Map.of(
                        "tool", "ctip_get_scout_activity",
                        "result", result
                ));
    }

    // ── ctip_get_self_improvement_proposals ───────────────────────────────────

    private Mono<Map<String, Object>> handleGetSelfImprovementProposals(Map<String, Object> args) {
        String status = stringArg(args, "status", "pending");

        return ctipClient.getSelfImprovementProposals(status)
                .map(proposals -> Map.of(
                        "tool", "ctip_get_self_improvement_proposals",
                        "count", proposals.size(),
                        "proposals", proposals
                ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object v = args.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
