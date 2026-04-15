package com.jpmc.cibap.mcp.server;

import com.jpmc.cibap.mcp.model.McpToolCallResponse;
import com.jpmc.cibap.mcp.tools.CibapToolHandler;
import com.jpmc.cibap.mcp.tools.CsipToolHandler;
import com.jpmc.cibap.mcp.tools.CtipToolHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolDispatchService {

    private final CibapToolHandler cibapToolHandler;
    private final CsipToolHandler  csipToolHandler;
    private final CtipToolHandler  ctipToolHandler;

    public Mono<McpToolCallResponse> dispatch(String toolName, Map<String, Object> arguments) {
        if (toolName.startsWith("cibap_")) {
            return cibapToolHandler.execute(toolName, arguments)
                    .map(result -> McpToolCallResponse.success(toolName, result))
                    .onErrorResume(ex -> Mono.just(McpToolCallResponse.failure(toolName, ex.getMessage())));
        }
        if (toolName.startsWith("csip_")) {
            return csipToolHandler.execute(toolName, arguments)
                    .map(result -> McpToolCallResponse.success(toolName, result))
                    .onErrorResume(ex -> Mono.just(McpToolCallResponse.failure(toolName, ex.getMessage())));
        }
        if (toolName.startsWith("ctip_")) {
            return ctipToolHandler.execute(toolName, arguments)
                    .map(result -> McpToolCallResponse.success(toolName, result))
                    .onErrorResume(ex -> Mono.just(McpToolCallResponse.failure(toolName, ex.getMessage())));
        }

        return Mono.just(McpToolCallResponse.failure(toolName, "No handler found for tool: " + toolName));
    }

}
