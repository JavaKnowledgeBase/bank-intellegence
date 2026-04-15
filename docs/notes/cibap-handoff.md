# Handoff Notes

Date: 2026-04-13
Repo: `C:\Users\rkafl\Documents\job-Projects\jpmc\cibap`

## Current State

We focused only on the non-skeleton services and got them running in Docker.

Runnable services now:
- `customer-agent-service` on `http://localhost:8081`
- `fraud-detection-service` on `http://localhost:8082`
- `loan-prescreen-service` on `http://localhost:8083`

Supporting services now:
- PostgreSQL on `5432`
- Redis on `6379`
- MongoDB on `27017`
- Kafka on `9092` and `29092`
- Kafka UI on `http://localhost:8090`
- Keycloak on `http://localhost:8180`
- WireMock on `http://localhost:8888`
- Redis Insight on `http://localhost:5540`

Not runnable yet by design because they are still skeleton modules:
- `api-gateway`
- `orchestration-service`
- `notification-service`

## Docker Work Completed

### docker-compose
Updated `docker-compose.yml` so the implemented services use container-friendly hostnames instead of `localhost`.

Important runtime wiring now used:
- Postgres hostname: `postgres`
- Redis hostname: `redis`
- Kafka brokers: `kafka:29092`
- Keycloak issuer base: `http://keycloak:8080`
- WireMock base: `http://wiremock:8080`

### Dockerfiles
Replaced stub Dockerfiles with real multi-stage Spring Boot builds for:
- `customer-agent-service/Dockerfile`
- `fraud-detection-service/Dockerfile`
- `loan-prescreen-service/Dockerfile`

### Keycloak health
Keycloak was running but marked unhealthy because the image did not contain `curl`.
We fixed `docker-compose.yml` by:
- setting `KC_HEALTH_ENABLED: "true"`
- changing the healthcheck to a bash TCP probe against port `9000`

Result: Keycloak now reports healthy in Docker.

## Service Fixes Completed

### loan-prescreen-service
This service needed the most repair.

Key fixes made:
- created `LoanPrescreenApplication.java`
- cleaned up `LoanController.java`
- fixed `LoanPrescreenService.java`
- added missing `LoanApplicationRepository.java`
- completed `LoanStateMachineConfig.java`
- added missing actions:
  - `CreditCheckAction.java`
  - `IncomeVerifyAction.java`
- repaired action classes:
  - `RiskScoreAction.java`
  - `NotifyAction.java`
- added `@Builder` to `PrescreenResponse.java`
- aligned `LoanState.java` with the configured workflow
- added `CreditBureauConfig.java` to provide the missing `WebClient` bean for `CreditBureauClient`

This service now builds and runs in Docker.

### customer-agent-service
No major code repair during the last phase, but Docker runtime wiring was corrected.

Observed behavior:
- root URL returns `401 Unauthorized`
- this is expected and confirms the app is alive but secured

### fraud-detection-service
No major code repair during the last phase, but Docker runtime wiring was corrected.

Observed behavior:
- service stays up in Docker
- root URL is likely protected or not intended as a browser endpoint

## Security / Browser Checks

For the running secured services, a browser hit to the root often returns `401`.
That is expected and means the app is reachable.

Useful quick checks:
- `http://localhost:8081`
- `http://localhost:8082`
- `http://localhost:8083`

A `401` here means "service is up, but protected".

`/actuator/health` is also secured for at least some services, so a `401` there is not a Docker issue.

## Dependency / Build Notes

### loan-prescreen-service pom.xml
We updated this module to make dependency resolution work and to reduce vulnerability issues.
Important points:
- added local Spring Boot BOM override for the service
- added explicit versions where the parent was not managing them cleanly
- replaced old WireMock coordinate with `org.wiremock:wiremock`
- switched R2DBC PostgreSQL usage to `org.postgresql:r2dbc-postgresql`
- added Flyway PostgreSQL support module
- added Resilience4j Reactor and AOP support where needed

### .gitignore
Created module-level `.gitignore` in `loan-prescreen-service/.gitignore`.

## PDFs Created

Created in `C:\Users\rkafl\Documents\job-Projects\jpmc\docs`:
- `cibap-app-links.pdf`
  - clickable localhost links for runnable apps and web UIs
- `mcp-server-explained.pdf`
  - plain-language explanation of MCP, examples from this project, and how agentic AI is achieved here

## MCP Understanding Captured

Main MCP guide referenced:
- `C:\Users\rkafl\Documents\job-Projects\jpmc\docs\services\06-mcp-server-guide.md`

Key takeaway:
- downstream services should expose tools via MCP
- orchestration-service should become the MCP client / host
- this reduces coupling and lets domain services own their tool schemas
- MCP is central to the future agentic architecture here

## Commands That Worked

Build implemented services:
- `docker compose build customer-agent-service fraud-detection-service loan-prescreen-service`

Bring up working stack:
- `docker compose up -d postgres redis mongodb kafka kafka-ui keycloak wiremock redis-insight customer-agent-service fraud-detection-service loan-prescreen-service`

Check status:
- `docker compose ps`
- `docker compose ps -a`

## Best Next Steps For The Big Build

When we come back, likely next big milestones are:
1. Build `orchestration-service` for real
   - Spring Boot app
   - LangChain4j MCP client wiring
   - connect to downstream MCP servers
   - add secured chat endpoint
2. Add MCP server capability into the implemented services
   - especially `customer-agent-service`, `fraud-detection-service`, `loan-prescreen-service`
   - expose `/mcp/sse` and `/mcp/message`
   - implement `@Tool` classes and MCP configuration from the guide
3. Build `notification-service`
   - Kafka consumer
   - notification workflow
4. Build `api-gateway`
   - route to orchestration and downstream services
5. Add production-quality local-dev support
   - consistent local profile configs
   - tokens / Keycloak test flow
   - app healthchecks for all real services

## Important Context To Remember

- The user wants momentum and continuity; avoid redoing discovery work.
- The user specifically asked not to spend time on skeleton services until ready to build them for real.
- The non-skeleton Docker stack is already in a good usable state.
- If browser requests fail with `401`, treat that as a security response first, not a Docker failure.
- The next "big thing" will probably be making the architecture truly agentic using MCP and a real orchestration service.
