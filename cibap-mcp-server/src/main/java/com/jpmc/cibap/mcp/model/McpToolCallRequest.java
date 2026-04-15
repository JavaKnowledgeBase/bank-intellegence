package com.jpmc.cibap.mcp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record McpToolCallRequest(
        @NotBlank String name,
        Map<String, Object> arguments
) {
    public McpToolCallRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}


