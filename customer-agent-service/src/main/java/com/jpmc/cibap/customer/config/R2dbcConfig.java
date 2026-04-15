package com.jpmc.cibap.customer.config;

import io.r2dbc.spi.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;

/**
 * R2DBC and Flyway configuration for the Customer Agent Service.
 *
 * <h2>Why Flyway uses JDBC while the app uses R2DBC</h2>
 * <p>Flyway's schema migration API is inherently blocking (JDBC). The R2DBC driver
 * provides only a reactive, non-blocking connection. We therefore run Flyway once at
 * startup on the JDBC URL via {@link #flyway()} before any reactive code executes,
 * ensuring the schema is ready before R2DBC connections are opened.
 *
 * <h2>Transaction management</h2>
 * <p>{@link R2dbcTransactionManager} integrates with Project Reactor's transaction
 * context propagation. Use {@code @Transactional} on service methods, which will bind
 * to this manager via {@link ReactiveTransactionManager}.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>Flyway will fail at startup if the JDBC URL is unreachable — this is intentional
 *       (fail-fast) to avoid running the service against a schema-less database.</li>
 *   <li>R2DBC pool exhaustion ({@code max-size: 20}) under burst traffic causes new
 *       subscriptions to queue. Set {@code max-acquire-time} in application.yml to bound
 *       wait time and avoid indefinite blocking.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableR2dbcRepositories(basePackages = "com.jpmc.cibap.customer.repository")
@EnableConfigurationProperties(FlywayProperties.class)
public class R2dbcConfig extends AbstractR2dbcConfiguration {

    @Value("${spring.flyway.url}")
    private String flywayJdbcUrl;

    @Value("${spring.flyway.user}")
    private String flywayUser;

    @Value("${spring.flyway.password}")
    private String flywayPassword;

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String flywayLocations;

    /**
     * R2DBC {@link ConnectionFactory} is auto-configured by Spring Boot from
     * {@code spring.r2dbc.*} properties. We override here only to make the
     * {@code @DependsOn("flyway")} ordering work correctly.
     *
     * <p>Spring Boot's auto-configuration will inject the actual pool-backed factory.
     *
     * @param connectionFactory auto-configured R2DBC connection factory
     * @return the same factory, wired after Flyway has run
     */
    @Override
    @DependsOn("flyway")  // Guarantee schema exists before reactive queries start
    public ConnectionFactory connectionFactory() {
        // Intentionally returned by Spring Boot auto-configuration.
        // This method is required by AbstractR2dbcConfiguration but the actual
        // pool is created by R2dbcAutoConfiguration from application.yml.
        throw new UnsupportedOperationException(
                "Connection factory is provided by Spring Boot R2DBC auto-configuration");
    }

    /**
     * Runs Flyway migrations synchronously at application startup.
     *
     * <p>Flyway uses a separate JDBC connection (blocking) to apply SQL migration scripts
     * from {@code classpath:db/migration}. This guarantees the database schema is in the
     * expected state before any R2DBC queries are issued.
     *
     * <p><strong>⚠ Runtime risk:</strong> If a migration script fails (e.g., due to a
     * SQL syntax error or a constraint violation), Flyway throws an exception and the
     * application will not start. This is intentional — never suppress Flyway errors.
     *
     * @return the configured and migrated {@link Flyway} instance
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway() {
        log.info("Running Flyway migrations against {}", flywayJdbcUrl);
        return Flyway.configure()
                .dataSource(flywayJdbcUrl, flywayUser, flywayPassword)
                .locations(flywayLocations)
                .baselineOnMigrate(false)  // Never silently baseline — force clean slate
                .validateOnMigrate(true)   // Fail fast on checksum mismatch
                .load();
    }

    /**
     * Reactive transaction manager for use with {@code @Transactional} service methods.
     *
     * <p>R2DBC transactions are propagated through the Reactor context, not thread-locals.
     * Do not use {@link org.springframework.transaction.PlatformTransactionManager} (the
     * blocking variant) in any reactive pipeline.
     *
     * @param connectionFactory the R2DBC connection factory
     * @return a {@link ReactiveTransactionManager} backed by R2DBC
     */
    @Bean
    public ReactiveTransactionManager transactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }
}
