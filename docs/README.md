# Bank Intelligence Documentation

This folder gathers the project documentation in one place while leaving source-local docs in their original folders.

## Application Projects

- `api-gateway` - Spring Cloud Gateway for routing, JWT validation, rate limiting, circuit breaking, and actuator metrics.
- `customer-agent-service` - Customer/accounts service.
- `fraud-detection-service` - Fraud detection service.
- `loan-prescreen-service` - Loan prescreen service.
- `notification-service` - Notification service.
- `orchestration-service` - Orchestration service.
- `support-intellegence` - Support intelligence app with backend, frontend, monitoring, and Kubernetes assets.
- `user-test-intellegence` - User test intelligence app with backend, frontend, Playwright runner, monitoring, and Kubernetes assets.
- `cibap-mcp-server` - MCP server project.

## Supporting Folders

- `infrastructure` - Local/runtime infrastructure assets such as Keycloak, SQL, and WireMock.
- `docs` - Consolidated documentation and runbooks.

## Documentation Index

- `architecture/mcp-server-architecture.md`
- `notes/cibap-handoff.md`
- `notes/user-test-intellegence-handoff.md`
- `runbooks/high-consumer-lag.md`
- `service-help/api-gateway-HELP.md`
- `service-help/cibap-mcp-server-HELP.md`
- `user-test-intellegence-README.md`

## Not Source Projects

The folder `api-gateway - Copy22` and zip files are local scratch/backup artifacts and should not be pushed as source.
Generated folders such as `target`, `node_modules`, `.pytest_cache`, and frontend `dist` output are ignored.
