package com.jpmc.cibap.customer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Spring Security configuration for the Customer Agent Service.
 *
 * <h2>Authentication model</h2>
 * <p>All protected endpoints require a valid JWT issued by the internal Keycloak realm
 * ({@code https://auth.chase.internal/realms/cibap}). The JWT is validated against the
 * JWKS URI derived from the configured {@code issuer-uri}.
 *
 * <h2>Authorization model</h2>
 * <ul>
 *   <li>{@code GET} account endpoints require the JWT scope {@code accounts:read}.</li>
 *   <li>{@code POST} support-request endpoint requires scope {@code accounts:write}.</li>
 *   <li>Actuator health and Prometheus metrics endpoints are open without authentication
 *       (required by Kubernetes liveness/readiness probes and Prometheus scraping).</li>
 * </ul>
 *
 * <p>{@link EnableReactiveMethodSecurity} activates {@code @PreAuthorize} annotations on
 * controller methods, enabling fine-grained per-method access control beyond the global
 * path-level rules below.
 *
 * <p><strong>⚠ Security risks:</strong>
 * <ul>
 *   <li>Never permit {@code /actuator/**} without restriction in internet-facing deployments.
 *       The Prometheus endpoint exposes service internals. Restrict at the ingress/ALB layer.</li>
 *   <li>CSRF is disabled because this is a stateless JWT API — sessions are never created.
 *       Do not enable session creation without re-enabling CSRF.</li>
 *   <li>JWT clock-skew tolerance defaults to 30 s. If the auth server and service clocks
 *       diverge beyond this, tokens will be rejected. Ensure NTP is synchronised.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity   // Enables @PreAuthorize on reactive controller methods
public class SecurityConfig {

    /**
     * Builds the reactive security filter chain.
     *
     * <p>Rules are evaluated top-down; more specific paths must come before broader ones.
     *
     * @param http the {@link ServerHttpSecurity} DSL builder
     * @return the configured {@link SecurityWebFilterChain}
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        log.info("Configuring security filter chain — JWT resource server mode");

        return http
                // No sessions — JWT is stateless, so CSRF is not applicable
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                .authorizeExchange(exchanges -> exchanges
                        // Kubernetes probes — must be unauthenticated
                        .pathMatchers("/actuator/health/**").permitAll()
                        // Prometheus metrics — restrict at network layer in production
                        .pathMatchers("/actuator/prometheus").permitAll()
                        // OpenAPI / Swagger UI — useful in lower environments
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**", "/webjars/**").permitAll()
                        // All API endpoints require authentication (scope checked per method via @PreAuthorize)
                        .pathMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/**").authenticated()
                        // Deny anything not explicitly permitted
                        .anyExchange().denyAll()
                )

                // Configure as a JWT resource server — validates against JWKS from issuer-uri
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {
                            // issuer-uri and jwk-set-uri are set in application.yml
                            log.debug("JWT resource server configured from application.yml issuer-uri");
                        })
                )

                .build();
    }
}
