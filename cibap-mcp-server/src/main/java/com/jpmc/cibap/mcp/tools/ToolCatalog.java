package com.jpmc.cibap.mcp.tools;

import com.jpmc.cibap.mcp.model.McpToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registry of all 30 MCP tools exposed by this gateway.
 *
 * Groups:
 *   cibap_*  — 8 CIBAP platform tools (health, metrics, logs, kafka, deployments, pods, summary, info)
 *   csip_*   — 10 CSIP tools (monitored apps, issues, audit, fix pipeline, self-improvement)
 *   ctip_*   — 12 CTIP tools (test cases, runs, gate, coverage, flaky, scout, self-improvement)
 */
@Component
public class ToolCatalog {

    public List<McpToolDefinition> listTools() {
        return List.of(
                // ── CIBAP (8) ────────────────────────────────────────────────────────────
                new McpToolDefinition(
                        "cibap_health_check",
                        "Check real-time health of one or more CIBAP services. " +
                        "Use 'all' or omit 'services' to check every service at once. " +
                        "Always call this first when investigating any platform issue.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "services", Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string",
                                                        "enum", List.of("customer-agent","fraud-detection",
                                                                "loan-prescreen","notification","orchestration","all")),
                                                "description", "Services to check. Default: all.",
                                                "default", List.of("all")
                                        )
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_get_logs",
                        "Retrieve recent log lines from a CIBAP service (from /actuator/logfile). " +
                        "Logs are filtered by level and an optional search term.",
                        Map.of(
                                "type", "object",
                                "required", List.of("service"),
                                "properties", Map.of(
                                        "service", Map.of("type", "string",
                                                "enum", List.of("customer-agent","fraud-detection","loan-prescreen","notification","orchestration")),
                                        "tail", Map.of("type", "integer", "default", 100, "maximum", 500),
                                        "levelFilter", Map.of("type", "string", "enum", List.of("ERROR","WARN","INFO","ALL"), "default", "ERROR"),
                                        "searchTerm", Map.of("type", "string", "description", "Filter lines containing this text")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_get_metrics",
                        "Fetch key Prometheus metrics for a CIBAP service. " +
                        "Returns a human-readable summary of JVM, HTTP, and thread metrics.",
                        Map.of(
                                "type", "object",
                                "required", List.of("service"),
                                "properties", Map.of(
                                        "service", Map.of("type", "string",
                                                "enum", List.of("customer-agent","fraud-detection","loan-prescreen","notification","orchestration")),
                                        "timeRangeMinutes", Map.of("type", "integer", "default", 30)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_get_kafka_status",
                        "Check Kafka broker health, topic details, and consumer group lag. " +
                        "High consumer lag indicates a service is falling behind on event processing.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "consumerGroups", Map.of("type", "array", "items", Map.of("type", "string")),
                                        "topics", Map.of("type", "array", "items", Map.of("type", "string"))
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_get_recent_deployments",
                        "List recent deployments across CIBAP services — who deployed, what commit, when.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string", "description", "Filter by service name, or omit for all"),
                                        "hoursAgo", Map.of("type", "integer", "default", 24, "maximum", 168)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_get_pod_status",
                        "Get Kubernetes pod status for a CIBAP service: pod count, restarts, resource usage. " +
                        "Returns a descriptive placeholder in local dev (no K8s access required).",
                        Map.of(
                                "type", "object",
                                "required", List.of("service"),
                                "properties", Map.of(
                                        "service", Map.of("type", "string",
                                                "enum", List.of("customer-agent","fraud-detection","loan-prescreen","notification","orchestration")),
                                        "includeTerminated", Map.of("type", "boolean", "default", false)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_platform_summary",
                        "Get a combined health snapshot across all CIBAP services. " +
                        "Combine with csip_get_issues and ctip_get_suite_result for a full stand-up briefing.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "includeMetrics", Map.of("type", "boolean", "default", false)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "cibap_get_service_info",
                        "Get static information about a CIBAP service: description, tech stack, port, tier, Kafka topics.",
                        Map.of(
                                "type", "object",
                                "required", List.of("service"),
                                "properties", Map.of(
                                        "service", Map.of("type", "string",
                                                "enum", List.of("customer-agent","fraud-detection","loan-prescreen","notification","orchestration"))
                                )
                        ),
                        true
                ),

                // ── CSIP (10) ────────────────────────────────────────────────────────────
                new McpToolDefinition(
                        "csip_list_monitored_apps",
                        "List all applications registered in CSIP for autonomous monitoring. " +
                        "Returns name, URL, tier, current status, and active issue count.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "teamId",  Map.of("type", "string"),
                                        "tier",    Map.of("type", "string", "enum", List.of("p0","p1","p2","p3","all")),
                                        "status",  Map.of("type", "string", "enum", List.of("healthy","degraded","down","all"))
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "csip_register_app",
                        "Register a new application for CSIP autonomous monitoring. " +
                        "CSIP will probe the URL and begin health checks immediately.",
                        Map.of(
                                "type", "object",
                                "required", List.of("url"),
                                "properties", Map.of(
                                        "url",         Map.of("type", "string", "description", "Application base URL"),
                                        "description", Map.of("type", "string"),
                                        "repoUrl",     Map.of("type", "string"),
                                        "tier",        Map.of("type", "string", "enum", List.of("p0","p1","p2","p3"), "default", "p2"),
                                        "teamId",      Map.of("type", "string")
                                )
                        ),
                        false
                ),
                new McpToolDefinition(
                        "csip_get_issues",
                        "Get issues currently detected and tracked by CSIP. " +
                        "Filterable by status, category, severity, and service.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status",   Map.of("type", "string",
                                                "enum", List.of("open","diagnosing","fixing","pr_open","escalated","resolved","all"),
                                                "default", "open"),
                                        "category", Map.of("type", "string", "enum", List.of("infrastructure","configuration","code","all")),
                                        "severity", Map.of("type", "string", "enum", List.of("p0","p1","p2","p3","all")),
                                        "service",  Map.of("type", "string"),
                                        "limit",    Map.of("type", "integer", "default", 20, "maximum", 100)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "csip_get_issue_detail",
                        "Get full detail for a specific CSIP issue: root cause, evidence, fix attempts, PR link.",
                        Map.of(
                                "type", "object",
                                "required", List.of("issueId"),
                                "properties", Map.of(
                                        "issueId", Map.of("type", "string")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "csip_trigger_fix_retry",
                        "Trigger a retry of the automated fix pipeline for a CSIP issue. " +
                        "Use when a fix attempt failed. Maximum 3 total attempts per issue.",
                        Map.of(
                                "type", "object",
                                "required", List.of("issueId"),
                                "properties", Map.of(
                                        "issueId",           Map.of("type", "string"),
                                        "additionalContext", Map.of("type", "string",
                                                "description", "Extra context to help CSIP generate a better fix")
                                )
                        ),
                        false
                ),
                new McpToolDefinition(
                        "csip_escalate_issue",
                        "Escalate a CSIP issue to human investigation. Marks issue as ESCALATED in the system.",
                        Map.of(
                                "type", "object",
                                "required", List.of("issueId"),
                                "properties", Map.of(
                                        "issueId",          Map.of("type", "string"),
                                        "escalationReason", Map.of("type", "string"),
                                        "urgency",          Map.of("type", "string", "enum", List.of("low","high"), "default", "high")
                                )
                        ),
                        false
                ),
                new McpToolDefinition(
                        "csip_get_audit_log",
                        "Retrieve CSIP's immutable audit log. " +
                        "Records every action: issue detected, fix attempted, PR created.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "eventType", Map.of("type", "string"),
                                        "service",   Map.of("type", "string"),
                                        "since",     Map.of("type", "string", "description", "ISO8601 timestamp"),
                                        "limit",     Map.of("type", "integer", "default", 50, "maximum", 500)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "csip_get_fix_pipeline_status",
                        "Get the status of active fix pipelines — current stage, elapsed time.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "issueId", Map.of("type", "string", "description", "Filter for a specific issue")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "csip_get_self_improvement_proposals",
                        "List pending self-improvement proposals from the CSIP Self-Improver. " +
                        "Each proposal is a PR against the CSIP codebase. Admin role required.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status", Map.of("type", "string",
                                                "enum", List.of("pending","approved","rejected","all"),
                                                "default", "pending")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "csip_trigger_self_improvement",
                        "Manually trigger a CSIP self-improvement analysis cycle. " +
                        "Normal schedule: 1 AM UTC daily. Admin role required.",
                        Map.of("type", "object", "properties", Map.of()),
                        false
                ),

                // ── CTIP (12) ────────────────────────────────────────────────────────────
                new McpToolDefinition(
                        "ctip_list_test_cases",
                        "List test cases in the Test Intelligence Platform. " +
                        "Filterable by service, status, and priority.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "service", Map.of("type", "string"),
                                        "status",  Map.of("type", "string",
                                                "enum", List.of("active","flaky","quarantined","deprecated","all"),
                                                "default", "active"),
                                        "limit",   Map.of("type", "integer", "default", 20, "maximum", 200)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_search_tests",
                        "Semantic search across all CTIP test cases. " +
                        "Use to find existing tests for a scenario, related tests, or potential duplicates.",
                        Map.of(
                                "type", "object",
                                "required", List.of("query"),
                                "properties", Map.of(
                                        "query",         Map.of("type", "string"),
                                        "serviceFilter", Map.of("type", "string"),
                                        "topK",          Map.of("type", "integer", "default", 10, "maximum", 50)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_create_test",
                        "Generate a new test case from a natural language scenario. " +
                        "CTIP generates Playwright TypeScript and enforces test data governance. " +
                        "Engineer role required.",
                        Map.of(
                                "type", "object",
                                "required", List.of("scenario", "targetService"),
                                "properties", Map.of(
                                        "scenario",      Map.of("type", "string", "description", "Natural language test scenario"),
                                        "targetService", Map.of("type", "string",
                                                "enum", List.of("customer-agent","fraud-detection","loan-prescreen")),
                                        "tags",          Map.of("type", "array", "items", Map.of("type", "string")),
                                        "priority",      Map.of("type", "string",
                                                "enum", List.of("critical","high","medium","low"), "default", "medium"),
                                        "linkedIssueId", Map.of("type", "string",
                                                "description", "CSIP issue this is a regression test for")
                                )
                        ),
                        false
                ),
                new McpToolDefinition(
                        "ctip_run_test",
                        "Execute a single test case. Returns immediately with a run ID; " +
                        "poll ctip_get_run_result after ~30 seconds for the outcome.",
                        Map.of(
                                "type", "object",
                                "required", List.of("testCaseId"),
                                "properties", Map.of(
                                        "testCaseId",  Map.of("type", "string"),
                                        "environment", Map.of("type", "string", "enum", List.of("local","staging"), "default", "staging")
                                )
                        ),
                        false
                ),
                new McpToolDefinition(
                        "ctip_run_suite",
                        "Execute all active test cases for a service in parallel K8s Jobs. " +
                        "Full suite completes in under 15 minutes. Returns a suite_run_id.",
                        Map.of(
                                "type", "object",
                                "required", List.of("service"),
                                "properties", Map.of(
                                        "service",        Map.of("type", "string",
                                                "enum", List.of("customer-agent","fraud-detection","loan-prescreen","all")),
                                        "tags",           Map.of("type", "array", "items", Map.of("type", "string"),
                                                "description", "Run only tests with these tags (e.g. regression)"),
                                        "priorityFilter", Map.of("type", "string",
                                                "enum", List.of("critical","high","all"), "default", "all")
                                )
                        ),
                        false
                ),
                new McpToolDefinition(
                        "ctip_get_run_result",
                        "Get the result of a specific test run: pass/fail, duration, error message.",
                        Map.of(
                                "type", "object",
                                "required", List.of("runId"),
                                "properties", Map.of(
                                        "runId", Map.of("type", "string")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_get_suite_result",
                        "Get aggregated results for a parallel suite run: total/passed/failed counts and gate decision.",
                        Map.of(
                                "type", "object",
                                "required", List.of("suiteRunId"),
                                "properties", Map.of(
                                        "suiteRunId", Map.of("type", "string")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_get_deployment_gate",
                        "Check if a specific commit passes the CTIP quality gate. " +
                        "Returns pass/block decision and any failures that caused a block.",
                        Map.of(
                                "type", "object",
                                "required", List.of("service", "commit"),
                                "properties", Map.of(
                                        "service", Map.of("type", "string"),
                                        "commit",  Map.of("type", "string")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_get_coverage",
                        "Get test coverage analysis for a service: endpoints covered, gaps, and AI-generated suggestions.",
                        Map.of(
                                "type", "object",
                                "required", List.of("service"),
                                "properties", Map.of(
                                        "service",            Map.of("type", "string"),
                                        "includeSuggestions", Map.of("type", "boolean", "default", true)
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_get_flaky_tests",
                        "List test cases with inconsistent pass/fail results. " +
                        "Shows flakiness rate over the last 20 runs.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "service",   Map.of("type", "string"),
                                        "threshold", Map.of("type", "number", "default", 0.15,
                                                "description", "Minimum flakiness rate to include")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_get_scout_activity",
                        "Get web scout activity: URLs examined, safety verdicts, accepted/rejected test patterns today.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "date",   Map.of("type", "string", "description", "YYYY-MM-DD, default: today"),
                                        "status", Map.of("type", "string", "enum", List.of("accepted","rejected","all"), "default", "all")
                                )
                        ),
                        true
                ),
                new McpToolDefinition(
                        "ctip_get_self_improvement_proposals",
                        "List CTIP self-improvement proposals (shadow mode — all go to PR for human review). " +
                        "Admin role required.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status", Map.of("type", "string",
                                                "enum", List.of("pending","approved","rejected","all"),
                                                "default", "pending")
                                )
                        ),
                        true
                )
        );
    }
}
