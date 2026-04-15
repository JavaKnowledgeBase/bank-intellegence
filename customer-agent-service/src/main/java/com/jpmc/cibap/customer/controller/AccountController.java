package com.jpmc.cibap.customer.controller;

import com.jpmc.cibap.customer.exception.ServiceUnavailableException;
import com.jpmc.cibap.customer.model.AccountSummary;
import com.jpmc.cibap.customer.model.SupportRequest;
import com.jpmc.cibap.customer.model.Transaction;
import com.jpmc.cibap.customer.service.AccountService;
import com.jpmc.cibap.customer.service.SupportRequestService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller for customer account operations.
 *
 * <p>Exposes three endpoints under {@code /api/v1/accounts}:
 * <ol>
 *   <li>{@code GET /{customerId}/summary} — aggregated account balance summary</li>
 *   <li>{@code GET /{customerId}/transactions} — filtered transaction history</li>
 *   <li>{@code POST /{customerId}/support-request} — async support ticket submission</li>
 * </ol>
 *
 * <h2>Security</h2>
 * <p>All endpoints require a valid JWT (Bearer token). Scope-based authorisation is
 * enforced via {@code @PreAuthorize} ({@code accounts:read} or {@code accounts:write}).
 *
 * <h2>Resilience pattern: Rate Limiter</h2>
 * <p>The {@code GET} endpoints are protected by the {@code api-rate-limiter}
 * (100 req/s per JVM). On breach, a HTTP 429 is returned immediately.
 *
 * <p><strong>Why:</strong> The summary endpoint triggers Redis reads and potentially DB
 * queries. Unthrottled bot traffic (e.g., a misconfigured polling loop) can saturate the
 * connection pool within seconds. The rate-limiter is the first line of defence.
 *
 * <h2>Input validation</h2>
 * <ul>
 *   <li>{@code customerId} is validated as a {@link UUID} — Spring automatically returns
 *       400 if the path variable is not a valid UUID string.</li>
 *   <li>Date range: {@code from} must be before {@code to}; validated programmatically.</li>
 *   <li>Support request body: validated via {@code @Valid} + Bean Validation annotations
 *       on {@link SupportRequest}.</li>
 * </ul>
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>A {@link UUID} path variable that is structurally valid but not present in the DB
 *       will produce an {@link com.jpmc.cibap.customer.exception.AccountNotFoundException}
 *       (HTTP 404) from the service layer — not a 400.</li>
 *   <li>If both the {@code from} and {@code to} params are missing the request fails with
 *       400 before reaching the controller body.</li>
 *   <li>WebFlux does not block the calling thread; the reactive pipeline is only subscribed
 *       by the framework when it writes the HTTP response. Do not call {@code .block()} here.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Customer account operations — summary, transactions, and support")
public class AccountController {

    private final AccountService accountService;
    private final SupportRequestService supportRequestService;

    /**
     * Rate-limiter for read endpoints. Injected from
     * {@link com.jpmc.cibap.customer.config.Resilience4jConfig#apiRateLimiter}.
     */
    private final RateLimiter apiRateLimiter;

    // ─── GET /summary ─────────────────────────────────────────────────────────

    /**
     * Returns an aggregated account summary for the specified customer.
     *
     * <p>The response includes all ACTIVE accounts with their current balances and a
     * summed {@code totalBalance}. Results are cached in Redis for 60 seconds (cache-aside).
     *
     * <p>Rate-limited to 100 requests/second per JVM instance. Requests exceeding the
     * limit receive HTTP 429 immediately.
     *
     * @param customerId the UUID of the customer
     * @return a {@link Mono} emitting the account summary (HTTP 200)
     */
    @GetMapping("/{customerId}/summary")
    @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
    @Timed(value = "customer.account.summary", description = "Account summary fetch latency")
    @Operation(
            summary = "Get account summary",
            description = "Returns aggregated balance summary for all ACTIVE accounts of the customer. "
                    + "Results are cached in Redis for 60 seconds.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary returned successfully",
                    content = @Content(schema = @Schema(implementation = AccountSummary.class))),
            @ApiResponse(responseCode = "400", description = "Invalid customerId UUID format",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient scope — requires accounts:read",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No active accounts found for customer",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Downstream service (DB/Redis) unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Mono<AccountSummary> getAccountSummary(
            @Parameter(description = "Customer UUID", required = true)
            @PathVariable UUID customerId) {

        log.info("[AccountController] GET /summary customerId={}", customerId);

        return accountService.getAccountSummary(customerId)
                // ── Resilience: Rate Limiter ──────────────────────────────────
                // Protects Redis + DB from bot-driven spikes. Triggers HTTP 429 on breach.
                .transform(RateLimiterOperator.of(apiRateLimiter))

                // ── Map rate-limit rejection to 429 ──────────────────────────
                .onErrorMap(RequestNotPermitted.class, ex -> {
                    log.warn("[AccountController] Rate limit exceeded for GET /summary customerId={}", customerId);
                    return new ServiceUnavailableException("Rate limit exceeded. Please slow down.", ex);
                });
    }

    // ─── GET /transactions ────────────────────────────────────────────────────

    /**
     * Returns up to 100 transactions per account for the customer within the given
     * date range, ordered newest-first.
     *
     * <p>Both {@code from} and {@code to} are required ISO-8601 instant parameters
     * (e.g., {@code 2025-01-01T00:00:00Z}). Validation ensures {@code from} is before
     * {@code to}.
     *
     * <p>Rate-limited to 100 requests/second per JVM instance.
     *
     * @param customerId the UUID of the customer
     * @param from       inclusive lower bound of the date range (ISO-8601)
     * @param to         inclusive upper bound of the date range (ISO-8601)
     * @return a {@link Flux} emitting transactions (may be empty if none found)
     */
    @GetMapping("/{customerId}/transactions")
    @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
    @Timed(value = "customer.transactions.list", description = "Transaction list fetch latency")
    @Operation(
            summary = "Get transaction history",
            description = "Returns up to 100 transactions per account within the date range, "
                    + "ordered newest-first. Date range is inclusive on both ends. "
                    + "The 100-row limit per account is a hard cap to protect memory.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transactions returned (may be empty)"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters — bad UUID, bad date format, or from > to",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient scope — requires accounts:read",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Database unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Flux<Transaction> getTransactions(
            @Parameter(description = "Customer UUID", required = true)
            @PathVariable UUID customerId,

            @Parameter(description = "Inclusive start of date range (ISO-8601, e.g. 2025-01-01T00:00:00Z)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Inclusive end of date range (ISO-8601, e.g. 2025-12-31T23:59:59Z)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        log.info("[AccountController] GET /transactions customerId={} from={} to={}", customerId, from, to);

        // ⚠ Runtime risk: inverted date range returns empty results silently in the DB.
        // Validate here to give the caller a clear 400 rather than a misleading empty 200.
        if (from.isAfter(to)) {
            log.warn("[AccountController] Invalid date range: from={} is after to={}", from, to);
            return Flux.error(new IllegalArgumentException(
                    "'from' must not be after 'to'. Received from=" + from + ", to=" + to));
        }

        return accountService.getTransactions(customerId, from, to)
                // ── Resilience: Rate Limiter ──────────────────────────────────
                .transform(RateLimiterOperator.of(apiRateLimiter))

                .onErrorMap(RequestNotPermitted.class, ex -> {
                    log.warn("[AccountController] Rate limit exceeded for GET /transactions customerId={}", customerId);
                    return new ServiceUnavailableException("Rate limit exceeded. Please slow down.", ex);
                });
    }

    // ─── POST /support-request ────────────────────────────────────────────────

    /**
     * Submits a customer support request asynchronously.
     *
     * <p>The request is validated, enriched with a server-side timestamp, and published
     * to the {@code customer-support-events} Kafka topic. The response is HTTP 202 Accepted
     * with the Kafka {@code messageId} as the body — the request has been accepted for
     * processing but not yet fully handled.
     *
     * <p>{@code customerId} is taken from the path variable and injected into the request
     * body server-side. Client-supplied {@code customerId} in the JSON body is ignored.
     *
     * @param customerId the UUID of the customer making the request
     * @param request    the support request payload (validated)
     * @return a {@link Mono} emitting the message ID string (HTTP 202)
     */
    @PostMapping("/{customerId}/support-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SCOPE_accounts:write')")
    @Timed(value = "customer.support.submit", description = "Support request submission latency")
    @Operation(
            summary = "Submit a support request",
            description = "Validates the request, enriches it with a server timestamp, and publishes "
                    + "it asynchronously to the customer-support-events Kafka topic. "
                    + "Returns HTTP 202 with a messageId for tracking.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Support request accepted for processing",
                    content = @Content(schema = @Schema(implementation = String.class,
                            example = "Support request submitted: 550e8400-e29b-41d4-a716-446655440000"))),
            @ApiResponse(responseCode = "400", description = "Validation failure on request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient scope — requires accounts:write",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No active account found for customer",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Kafka messaging system unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Mono<String> submitSupportRequest(
            @Parameter(description = "Customer UUID", required = true)
            @PathVariable UUID customerId,

            @Valid @RequestBody SupportRequest request) {

        log.info("[AccountController] POST /support-request customerId={} category={} priority={}",
                customerId, request.getCategory(), request.getPriority());

        // Inject customerId server-side — never trust client-supplied customerId in body
        request.setCustomerId(customerId);

        return supportRequestService.submit(request)
                .map(messageId -> "Support request submitted: " + messageId)
                .doOnSuccess(msg -> log.info("[AccountController] Support request accepted: {}", msg))
                .doOnError(ex -> log.error("[AccountController] Support request failed customerId={}: {}",
                        customerId, ex.getMessage(), ex));
    }
}
