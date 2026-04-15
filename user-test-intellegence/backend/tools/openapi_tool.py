class OpenAPITool:
    def coverage_targets(self, service: str) -> list[str]:
        defaults = {
            "customer-agent-service": ["/customers", "/customers/{id}"],
            "fraud-detection-service": ["/fraud/check", "/fraud/history"],
            "loan-prescreen-service": ["/prescreen", "/offers"],
        }
        return defaults.get(service, [])
