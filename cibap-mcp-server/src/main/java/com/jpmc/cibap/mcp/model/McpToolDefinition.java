package com.jpmc.cibap.mcp.model;

import java.util.Map;

public record McpToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean readOnly
) {
}
