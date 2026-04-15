---
title: "CIBAP MCP Server — Architecture & Design"
subtitle: "How the Model Context Protocol Gateway Connects Claude to the CIBAP Platform"
author: "CIBAP Engineering"
date: "April 2026"
geometry: margin=1.2in
fontsize: 11pt
linestretch: 1.4
colorlinks: true
linkcolor: NavyBlue
urlcolor: NavyBlue
toc: true
toc-depth: 2
header-includes:
  - \usepackage{fancyhdr}
  - \pagestyle{fancy}
  - \fancyhead[L]{CIBAP MCP Server}
  - \fancyhead[R]{Architecture \& Design}
  - \fancyfoot[C]{\thepage}
  - \usepackage{xcolor}
  - \definecolor{NavyBlue}{RGB}{0,0,128}
---

\newpage

# The World This MCP Server Lives In

The CIBAP platform is a collection of seven running services spread across your local environment.
Five of them are Java Spring Boot microservices that handle real banking domain work.
One manages customer data and support requests.
One scores transactions for fraud in real time using Drools rules and a SageMaker ML model.
One runs a six-state loan pre-screening workflow managed by Spring State Machine.
One dispatches notifications via Kafka and SNS/SES.
And one is an AI orchestration service powered by LangChain4j and Amazon Bedrock Claude.
Those five services sit on ports **8081 through 8085**.

Then there are two newer Python FastAPI services added to the project.
One is **CSIP** — the CIBAP Support Intelligence Platform — running on port **8092**.
It monitors deployed applications for issues, detects problems autonomously, opens fix pipelines,
and maintains a full audit log of every action it takes.
The other is **CTIP** — the CIBAP Test Intelligence Platform — running on port **8091**.
It manages test cases with semantic search, runs test suites in parallel, evaluates deployment
gates, tracks flaky tests, and operates a "web scout" that continuously watches for new test
scenarios.

None of these seven services knows anything about Claude or AI tooling.
They are plain HTTP services that speak ordinary REST.
That gap — between an AI assistant and a fleet of domain services — is exactly what the MCP
server bridges.

---

# What the MCP Server Actually Is

The `cibap-mcp-server` is a translator. It sits on port **8095** and connects an AI assistant
(Claude) to those seven services using a protocol called the **Model Context Protocol (MCP)**.

MCP is a simple JSON contract with two sides. On one side, an AI client asks
*"what can you do?"* and receives back a list of named **tools** — each with a description,
typed input parameters, and a flag indicating whether calling it has side effects.
On the other side, the AI client says *"call this tool with these arguments"* and receives a
structured result it can reason about and present to a user.

The MCP server exposes exactly **two HTTP endpoints** to the outside world:

- `GET  /api/v1/mcp/tools` — returns the catalogue of all 30 tools as a JSON array
- `POST /api/v1/mcp/tools/call` — accepts a tool name and arguments, executes the tool, returns the result

When Claude is configured with this MCP server's URL, it loads the tool list once at startup.
From that point forward, Claude can autonomously decide to call `cibap_health_check` to check
if services are running, or `csip_get_issues` to find open problems in production, or
`ctip_run_suite` to trigger a test run against a service — all by making HTTP calls to this
single gateway on port 8095. Claude never directly reaches port 8081, 8082, 8091, or 8092.
It only ever talks to the MCP server.

---

# How a Request Flows Through the Code

To make this concrete, walk through a real call: Claude posts the following to the server:

```json
{
  "name": "csip_get_issues",
  "arguments": {
    "status": "open",
    "severity": "critical"
  }
}
```

**Step 1 — Controller.** The request enters `McpController`, a standard Spring WebFlux
`@RestController`. It deserialises the body into a `McpToolCallRequest` record (a Java 21
record with two fields: `name` and `arguments`). The `@Valid` annotation triggers Jakarta
Bean Validation — if `name` is blank, a 400 is returned immediately without touching any
downstream service.

**Step 2 — Dispatch.** The controller hands the validated request to `ToolDispatchService`.
This component is the traffic director. It inspects the tool name's prefix and branches:

- Names starting with `cibap_` → `CibapToolHandler`
- Names starting with `csip_`  → `CsipToolHandler`
- Names starting with `ctip_`  → `CtipToolHandler`
- Anything else → immediate failure response, no downstream call made

For our example, the `csip_` prefix routes to `CsipToolHandler`.

**Step 3 — Handler.** The handler's job is argument translation, nothing more. It pulls
`status`, `category`, `severity`, `service`, and `limit` out of the arguments map, normalises
the string `"all"` to `null` (which means "no filter" to the client), and calls
`csipClient.getIssues(status, category, severity, service, limit)`. The handler knows nothing
about HTTP — it deals only in Java types.

**Step 4 — Client.** `CsipClient` holds a Spring `WebClient` whose base URL is
`http://localhost:8092`. It constructs the URI with query parameters, calls CSIP's
`GET /api/v1/issues` endpoint, and deserialises the response body into
`List<Map<String, Object>>` using a `ParameterizedTypeReference` — the correct Spring pattern
that avoids raw type casts. It applies a 10-second timeout. If anything goes wrong — CSIP is
down, the call times out, or CSIP returns a 5xx — the error is caught with `onErrorResume` and
converted to a structured error map. The exception never propagates to the controller.

**Step 5 — Response.** The result flows back up through the handler (which adds the tool name
and item count), through the dispatch service (which wraps it in `McpToolCallResponse.success`),
through the controller (which serialises to JSON), and back to Claude.

The entire pipeline is **non-blocking**. Every method returns a `Mono<T>`. No thread is held
waiting for a network response. The Spring WebFlux event loop manages all concurrency.

---

# The Three Clients and What They Know

Each of the three downstream worlds has a dedicated HTTP client with its own knowledge of that
system's API surface.

## CibapClient — Five Services, One Client

`CibapClient` is the most complex client because it talks to five different services, not one.
Each service has its own base URL stored in `McpProperties.cibapServiceUrls` — a
`Map<String, String>` that maps service names like `"fraud-detection"` to URLs like
`http://localhost:8082`. The client has a `urlFor(service)` helper that looks up the right URL
for each call, then constructs a fresh `WebClient` instance for that base URL using an injected
`WebClient.Builder`.

The endpoints it calls are mostly **Spring Boot Actuator** endpoints that all five services
expose automatically:

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Service health status (UP/DOWN, components) |
| `GET /actuator/prometheus` | Raw Prometheus metrics text |
| `GET /actuator/logfile` | Log file content for tailing |
| `GET /actuator/info` | Build and environment metadata |

For Kafka status and recent deployment history, it calls custom REST endpoints on the
orchestration service that have not been implemented yet. Rather than failing hard, the client
catches those connection errors and returns a `Map` with `"note"` and `"hint"` keys explaining
what endpoint needs to be added — so Claude can still convey useful information.

Pod status is handled differently: since Kubernetes API access is not available in local
development, `getPodStatus` immediately returns a static placeholder without making any HTTP
call at all.

## CsipClient — Support Intelligence

`CsipClient` talks to one fixed base URL — port 8092 — and maps each of its 10 methods
directly to CSIP's FastAPI routes:

| MCP Tool | HTTP Call to CSIP |
|---|---|
| `csip_list_monitored_apps` | `GET  /api/v1/apps` |
| `csip_register_app` | `POST /api/v1/apps` |
| `csip_get_issues` | `GET  /api/v1/issues` |
| `csip_get_issue_detail` | `GET  /api/v1/issues/{issueId}` |
| `csip_trigger_fix_retry` | `POST /api/v1/issues/{issueId}/escalate` |
| `csip_escalate_issue` | `POST /api/v1/issues/{issueId}/escalate` |
| `csip_get_audit_log` | `GET  /api/v1/audit` |
| `csip_get_fix_pipeline_status` | `GET  /api/v1/system/pipelines` |
| `csip_get_self_improvement_proposals` | placeholder (not in CSIP backend yet) |
| `csip_trigger_self_improvement` | placeholder (not in CSIP backend yet) |

The `trigger_fix_retry` and escalation tools both route through CSIP's `/escalate` endpoint
because CSIP does not yet have a dedicated retry endpoint. The self-improvement tools return
structured placeholder maps with `hint` fields guiding what CSIP would need to implement.

## CtipClient — Test Intelligence

`CtipClient` talks to port 8091 and covers CTIP's full API surface:

| MCP Tool | HTTP Call to CTIP |
|---|---|
| `ctip_list_test_cases` | `GET  /api/v1/test-cases` |
| `ctip_search_tests` | `GET  /api/v1/search?q=...` |
| `ctip_create_test` | `POST /api/v1/test-cases` *(30s timeout)* |
| `ctip_run_test` | `POST /api/v1/runs/suite/default` |
| `ctip_run_suite` | `POST /api/v1/runs/suite/{service}` |
| `ctip_get_run_result` | `GET  /api/v1/runs/suites` (then filter by ID) |
| `ctip_get_suite_result` | `GET  /api/v1/runs/suites/{suiteRunId}` |
| `ctip_get_deployment_gate` | `GET  /api/v1/gate/{service}/{commit}` |
| `ctip_get_coverage` | `GET  /api/v1/coverage?service=...` |
| `ctip_get_flaky_tests` | `GET  /api/v1/test-cases?status=flaky` |
| `ctip_get_scout_activity` | `GET  /api/v1/scout/observations` |
| `ctip_get_self_improvement_proposals` | `GET  /api/v1/self-improvement/proposals` |

Two design decisions are worth noting. First, `ctip_create_test` uses a **30-second timeout**
instead of the standard 10 seconds, because generating a test case from a natural language
scenario requires CTIP to make its own LLM call internally — that takes longer than a normal
data query. Second, `ctip_run_test` (run a single test) has no dedicated endpoint in CTIP
because CTIP's execution model is fundamentally **suite-based**. The client tunnels single-test
execution through the suite runner by putting one test case ID in the `test_case_ids` array of
the payload.

---

# Configuration: The Single Source of Truth

`application.yaml` is the centralised configuration file. It declares:

- **Server port:** 8095
- **MCP settings:** all seven downstream service URLs, dev auth bypass flag
- **Resilience4j circuit breakers:** one per downstream system (CSIP, CTIP, CIBAP), each with
  a sliding window of 10 calls, opening when 50% fail, attempting recovery after 30 seconds
- **Spring Actuator:** health, info, and Prometheus endpoints exposed
- **Logging levels:** DEBUG for `com.jpmc.cibap` package, INFO for Spring internals

`McpProperties` is the typed Java representation of those `mcp.*` YAML keys.
Spring Boot's `@ConfigurationProperties(prefix = "mcp")` annotation automatically populates
all fields from the YAML at startup. No client ever hardcodes a port number — they always ask
`McpProperties` for the URL they need.

---

# Security and Error Handling

`SecurityConfig` reads the `devBypassAuth` flag from `McpProperties`. In local development
this flag is `true`, so the WebFlux security filter chain calls `permitAll()` on all exchanges
and disables CSRF. No token is required. In production the flag would be `false`: the chain
requires a valid Bearer JWT on all non-actuator paths, validated against a configured OAuth2
issuer URI.

`GlobalExceptionHandler` is annotated with `@RestControllerAdvice`, which registers it as a
global exception interceptor across all controllers. It catches `IllegalArgumentException` —
thrown by the dispatch service's default branch when an unrecognised tool name arrives — and
converts it to a clean 400 JSON response with a structured `error` field, rather than letting
Spring emit a generic error page.

---

# The Tool Catalogue

`ToolCatalog` is a static registry — a `@Component` bean that constructs all 30
`McpToolDefinition` records at Spring startup and stores them in a list. Each definition
carries four fields:

- **`name`** — the exact string Claude sends in the `name` field of a tool call request
- **`description`** — a paragraph written for Claude to read, explaining what the tool does,
  when to use it, and what the result looks like
- **`inputSchema`** — a JSON Schema-style map specifying parameter names, types, defaults,
  enums, and whether a parameter is required
- **`readOnly`** — `true` if the call has no side effects; `false` if it creates, modifies, or
  triggers something

Claude uses the `description` to decide *when* to call a tool and `inputSchema` to know *how*
to populate the arguments. The `readOnly` flag lets Claude know whether a tool is safe to call
in a read-only context (like answering "what is the health of the platform?") versus requiring
explicit user intent (like "create a test case" or "trigger a fix retry").

---

# The Full Relationship Map

```
Claude (AI client)
    |
    |  MCP Protocol — JSON over HTTP
    |
    v
+----------------------------------+
|   McpController  (port 8095)    |
|   GET  /api/v1/mcp/tools        |
|   POST /api/v1/mcp/tools/call   |
+----------------------------------+
    |
    v
ToolDispatchService
    |
    +-- cibap_* -----> CibapToolHandler -----> CibapClient
    |                                              |
    |                        +--------+---------+--------+---------+
    |                        |        |         |        |         |
    |                     :8081    :8082     :8083    :8084     :8085
    |                  customer  fraud-det  loan-pre  notific  orchestr
    |                 /actuator /actuator  /actuator /actuator /actuator
    |
    +-- csip_* ------> CsipToolHandler -----> CsipClient
    |                                             |
    |                               http://localhost:8092
    |                               CSIP FastAPI  (Python)
    |                               /api/v1/apps
    |                               /api/v1/issues
    |                               /api/v1/audit
    |                               /api/v1/system
    |
    +-- ctip_* ------> CtipToolHandler -----> CtipClient
                                                  |
                                      http://localhost:8091
                                      CTIP FastAPI  (Python)
                                      /api/v1/test-cases
                                      /api/v1/runs
                                      /api/v1/gate
                                      /api/v1/coverage
                                      /api/v1/scout
                                      /api/v1/self-improvement
```

Every arrow that crosses a process boundary is a non-blocking WebClient HTTP call with a
timeout and a graceful error fallback. Claude sees exactly one server. The seven downstream
services see normal REST clients. The MCP server is the only component in the platform that
holds a complete map of everyone else.

---

# Tool Inventory

## CIBAP Tools (8 tools)

| Tool Name | Description | Read Only |
|---|---|:---:|
| `cibap_health_check` | Health status of one or all services | Yes |
| `cibap_get_metrics` | Prometheus metrics summary for a service | Yes |
| `cibap_get_logs` | Filtered log lines for a service | Yes |
| `cibap_get_kafka_status` | Kafka topic and consumer group status | Yes |
| `cibap_get_recent_deployments` | Deployment history within N hours | Yes |
| `cibap_get_pod_status` | Kubernetes pod status for a service | Yes |
| `cibap_platform_summary` | Health of all services in one call | Yes |
| `cibap_get_service_info` | Metadata and static info about a service | Yes |

## CSIP Tools (10 tools)

| Tool Name | Description | Read Only |
|---|---|:---:|
| `csip_list_monitored_apps` | List apps being monitored by CSIP | Yes |
| `csip_register_app` | Register a new app for monitoring | No |
| `csip_get_issues` | Query open/resolved/all issues | Yes |
| `csip_get_issue_detail` | Full detail for a specific issue | Yes |
| `csip_trigger_fix_retry` | Retry the automated fix pipeline | No |
| `csip_escalate_issue` | Escalate an issue for human review | No |
| `csip_get_audit_log` | Audit trail of CSIP actions | Yes |
| `csip_get_fix_pipeline_status` | Status of active fix pipelines | Yes |
| `csip_get_self_improvement_proposals` | Pending self-improvement proposals | Yes |
| `csip_trigger_self_improvement` | Apply a self-improvement proposal | No |

## CTIP Tools (12 tools)

| Tool Name | Description | Read Only |
|---|---|:---:|
| `ctip_list_test_cases` | List test cases with optional filters | Yes |
| `ctip_search_tests` | Semantic search over test cases | Yes |
| `ctip_create_test` | Generate a test case from a scenario | No |
| `ctip_run_test` | Execute a single test case | No |
| `ctip_run_suite` | Run the full test suite for a service | No |
| `ctip_get_run_result` | Result of a single test run | Yes |
| `ctip_get_suite_result` | Aggregated result of a suite run | Yes |
| `ctip_get_deployment_gate` | Gate decision for a CI/CD commit | Yes |
| `ctip_get_coverage` | Test coverage analysis for a service | Yes |
| `ctip_get_flaky_tests` | Tests with inconsistent pass/fail history | Yes |
| `ctip_get_scout_activity` | Web scout observations and findings | Yes |
| `ctip_get_self_improvement_proposals` | CTIP self-improvement proposals | Yes |
