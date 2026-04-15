package com.jpmc.cibap.mcp.server;

import com.jpmc.cibap.mcp.model.McpToolCallRequest;
import com.jpmc.cibap.mcp.model.McpToolCallResponse;
import com.jpmc.cibap.mcp.model.McpToolDefinition;
import com.jpmc.cibap.mcp.tools.ToolCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class McpController {

    private final ToolCatalog toolCatalog;
    private final ToolDispatchService toolDispatchService;

    /** Root — quick sanity check that the server is up. */
    @GetMapping(path = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> root() {
        return Map.of(
                "service", "cibap-mcp-server",
                "status", "UP",
                "tools", toolCatalog.listTools().size(),
                "endpoints", Map.of(
                        "listTools", "GET  /api/v1/mcp/tools",
                        "callTool",  "POST /api/v1/mcp/tools/call",
                        "health",    "GET  /actuator/health"
                )
        );
    }

    @GetMapping(path = "/api/v1/mcp/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<McpToolDefinition> listTools() {
        return toolCatalog.listTools();
    }

    @PostMapping(path = "/api/v1/mcp/tools/call", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<McpToolCallResponse> callTool(@Valid @RequestBody McpToolCallRequest request) {
        return toolDispatchService.dispatch(request.name(), request.arguments());
    }
}
