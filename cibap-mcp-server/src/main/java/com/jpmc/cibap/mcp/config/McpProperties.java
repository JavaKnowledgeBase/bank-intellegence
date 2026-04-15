package com.jpmc.cibap.mcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private String cibapBaseUrl = "http://localhost:8081";
    private String csipBaseUrl  = "http://localhost:8092";
    private String ctipBaseUrl  = "http://localhost:8091";
    private boolean devBypassAuth = true;

    /**
     * Per-service base URLs for the 5 CIBAP downstream services.
     * Keys: customer-agent, fraud-detection, loan-prescreen, notification, orchestration
     */
    private Map<String, String> cibapServiceUrls = new HashMap<>(Map.of(
            "customer-agent",  "http://localhost:8081",
            "fraud-detection", "http://localhost:8082",
            "loan-prescreen",  "http://localhost:8083",
            "notification",    "http://localhost:8084",
            "orchestration",   "http://localhost:8085"
    ));

    /** Resolve a service name to its base URL (falls back to cibapBaseUrl). */
    public String urlFor(String service) {
        return cibapServiceUrls.getOrDefault(service, cibapBaseUrl);
    }
}
