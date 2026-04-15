package com.jpmc.cibap.mcp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security configuration for the CIBAP MCP Server.
 *
 * Dev mode (mcp.dev-bypass-auth=true): all requests permitted, no JWT required.
 * This lets you call tools from Claude Code or curl without a token locally.
 *
 * Production: remove the permitAll() branch and configure your JWT issuer URI
 * in application-prod.yaml under spring.security.oauth2.resourceserver.jwt.issuer-uri.
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final McpProperties mcpProperties;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        if (mcpProperties.isDevBypassAuth()) {
            // Dev mode: permit everything — no token checks
            return http
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                    .build();
        }

        // Production mode: require Bearer JWT on all tool endpoints
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }
}
