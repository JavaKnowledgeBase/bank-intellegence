package com.jpmc.cibap.mcp.tools;

import com.jpmc.cibap.mcp.client.CsipClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Handles all 10 csip_* MCP tools by delegating to CsipClient.
 */
@Component
@RequiredArgsConstructor
public class CsipToolHandler {

    private final CsipClient csipClient;

    public Mono<Map<String, Object>> execute(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "csip_list_monitored_apps"           -> handleListMonitoredApps(arguments);
            case "csip_register_app"                  -> handleRegisterApp(arguments);
            case "csip_get_issues"                    -> handleGetIssues(arguments);
            case "csip_get_issue_detail"              -> handleGetIssueDetail(arguments);
            case "csip_trigger_fix_retry"             -> handleTriggerFixRetry(arguments);
            case "csip_escalate_issue"                -> handleEscalateIssue(arguments);
            case "csip_get_audit_log"                 -> handleGetAuditLog(arguments);
            case "csip_get_fix_pipeline_status"       -> handleGetFixPipelineStatus(arguments);
            case "csip_get_self_improvement_proposals"-> handleGetSelfImprovementProposals(arguments);
            case "csip_trigger_self_improvement"      -> handleTriggerSelfImprovement(arguments);
            default -> Mono.error(new IllegalArgumentException("Unsupported CSIP tool: " + toolName));
        };
    }

    // ── csip_list_monitored_apps ──────────────────────────────────────────────

    private Mono<Map<String, Object>> handleListMonitoredApps(Map<String, Object> args) {
        String teamId = stringArg(args, "teamId", null);
        String tier   = stringArg(args, "tier", null);
        String status = stringArg(args, "status", null);

        return csipClient.listMonitoredApps(teamId, tier, status)
                .map(apps -> Map.of(
                        "tool", "csip_list_monitored_apps",
                        "count", apps.size(),
                        "apps", apps
                ));
    }

    // ── csip_register_app ─────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleRegisterApp(Map<String, Object> args) {
        String url         = String.valueOf(args.get("url"));
        String description = stringArg(args, "description", null);
        String repoUrl     = stringArg(args, "repoUrl", null);
        String tier        = stringArg(args, "tier", "p2");
        String teamId      = stringArg(args, "teamId", "default");

        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("url", url);
        payload.put("tier", tier);
        payload.put("team_id", teamId);
        if (description != null) payload.put("description", description);
        if (repoUrl != null)     payload.put("repo_url", repoUrl);

        return csipClient.registerApp(payload)
                .map(result -> Map.of(
                        "tool", "csip_register_app",
                        "result", result
                ));
    }

    // ── csip_get_issues ───────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetIssues(Map<String, Object> args) {
        String status   = stringArg(args, "status", "open");
        String category = stringArg(args, "category", null);
        String severity = stringArg(args, "severity", null);
        String service  = stringArg(args, "service", null);
        int    limit    = intArg(args, "limit", 20);

        // "all" means no filter
        if ("all".equalsIgnoreCase(status))   status   = null;
        if ("all".equalsIgnoreCase(category)) category = null;
        if ("all".equalsIgnoreCase(severity)) severity = null;

        return csipClient.getIssues(status, category, severity, service, limit)
                .map(issues -> Map.of(
                        "tool", "csip_get_issues",
                        "count", issues.size(),
                        "issues", issues
                ));
    }

    // ── csip_get_issue_detail ─────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetIssueDetail(Map<String, Object> args) {
        String issueId = String.valueOf(args.get("issueId"));

        return csipClient.getIssueDetail(issueId)
                .map(detail -> Map.of(
                        "tool", "csip_get_issue_detail",
                        "issueId", issueId,
                        "detail", detail
                ));
    }

    // ── csip_trigger_fix_retry ────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleTriggerFixRetry(Map<String, Object> args) {
        String issueId           = String.valueOf(args.get("issueId"));
        String additionalContext = stringArg(args, "additionalContext", null);

        return csipClient.triggerFixRetry(issueId, additionalContext)
                .map(result -> Map.of(
                        "tool", "csip_trigger_fix_retry",
                        "issueId", issueId,
                        "result", result
                ));
    }

    // ── csip_escalate_issue ───────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleEscalateIssue(Map<String, Object> args) {
        String issueId          = String.valueOf(args.get("issueId"));
        String escalationReason = stringArg(args, "escalationReason", "Escalated via MCP tool");

        return csipClient.escalateIssue(issueId, escalationReason)
                .map(result -> Map.of(
                        "tool", "csip_escalate_issue",
                        "issueId", issueId,
                        "result", result
                ));
    }

    // ── csip_get_audit_log ────────────────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetAuditLog(Map<String, Object> args) {
        String eventType = stringArg(args, "eventType", null);
        String service   = stringArg(args, "service", null);
        int    limit     = intArg(args, "limit", 50);

        return csipClient.getAuditLog(eventType, service, limit)
                .map(records -> Map.of(
                        "tool", "csip_get_audit_log",
                        "count", records.size(),
                        "records", records
                ));
    }

    // ── csip_get_fix_pipeline_status ──────────────────────────────────────────

    private Mono<Map<String, Object>> handleGetFixPipelineStatus(Map<String, Object> args) {
        String issueId = stringArg(args, "issueId", null);

        return csipClient.getFixPipelineStatus(issueId)
                .map(pipelines -> Map.of(
                        "tool", "csip_get_fix_pipeline_status",
                        "activePipelines", pipelines.size(),
                        "pipelines", pipelines
                ));
    }

    // ── csip_get_self_improvement_proposals ───────────────────────────────────

    private Mono<Map<String, Object>> handleGetSelfImprovementProposals(Map<String, Object> args) {
        String status = stringArg(args, "status", "pending");

        return csipClient.getSelfImprovementProposals(status)
                .map(result -> Map.of(
                        "tool", "csip_get_self_improvement_proposals",
                        "result", result
                ));
    }

    // ── csip_trigger_self_improvement ─────────────────────────────────────────

    private Mono<Map<String, Object>> handleTriggerSelfImprovement(Map<String, Object> args) {
        return csipClient.triggerSelfImprovement()
                .map(result -> Map.of(
                        "tool", "csip_trigger_self_improvement",
                        "result", result
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
