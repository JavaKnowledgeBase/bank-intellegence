package com.jpmc.cibap.mcp.model;

import java.util.Map;

public record McpToolCallResponse(
        String toolName,
        boolean success,
        Map<String, Object> result,
        String error
) {
    public static McpToolCallResponse success(String toolName, Map<String, Object> result) {
        return new McpToolCallResponse(toolName, true, result, null);
    }

    public static McpToolCallResponse failure(String toolName, String error) {
        return new McpToolCallResponse(toolName, false, null, error);
    }
}
