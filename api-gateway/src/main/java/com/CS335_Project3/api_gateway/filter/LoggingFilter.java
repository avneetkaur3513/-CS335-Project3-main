package com.CS335_Project3.api_gateway.filter;

import com.CS335_Project3.api_gateway.logging.RequestLogger;
import com.CS335_Project3.api_gateway.metrics.MetricsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import com.CS335_Project3.api_gateway.RateLimiter;
import com.CS335_Project3.api_gateway.metrics.BotDetector;
import org.springframework.web.util.ContentCachingResponseWrapper;

//@Component makes it run once only, making all filters share the same log list
//@Order(1) ensures LoggingFilter runs first and wraps the entire chain
//so blocked requests which were not getting logged can now get logged
@Component
@Order(1)
public class LoggingFilter extends OncePerRequestFilter {

    //we inject RequestLogger, MetricsService, RateLimiter and BotDetector so we can record, measure and detect on every request
    private final RequestLogger requestLogger;
    private final MetricsService metricsService;
    private final RateLimiter rateLimiter;
    private final BotDetector botDetector;

    public LoggingFilter(RequestLogger requestLogger, MetricsService metricsService, RateLimiter rateLimiter, BotDetector botDetector) {
        this.requestLogger  = requestLogger;
        this.metricsService = metricsService;
        this.rateLimiter    = rateLimiter;
        this.botDetector    = botDetector;
    }

    //paths excluded from logging so browser generated requests dont pollute the metrics data
    private static final java.util.List<String> EXCLUDED_PATHS =
            java.util.List.of("/health", "/metrics", "/metrics/logs", "/metrics/dashboard", "/favicon.ico", "/dashboard");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        //skips logging for internal/static paths
        if (EXCLUDED_PATHS.contains(request.getRequestURI())
                || request.getRequestURI().startsWith("/dashboard/")
                || request.getRequestURI().startsWith("/metrics/dashboard/client/")
                || request.getRequestURI().startsWith("/config/rate-limit/")) {
            chain.doFilter(request, response);
            return;
        }

        //wrap the response so we can always read the status after the chain finishes
        ContentCachingResponseWrapper wrappedResponse =
                new ContentCachingResponseWrapper(response);

        //grab request info
        long startedAt = System.currentTimeMillis();
        String apiKey = request.getHeader("X-API-Key");
        String path   = request.getRequestURI();
        String tenantId = request.getHeader("X-Tenant-Id");
        String appId = request.getHeader("X-App-Id");

        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "MISSING";
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        if (appId == null || appId.isBlank()) {
            appId = "default";
        }

        //gets the client IP and which rate limiting algorithm they are assigned to
        String ip        = request.getRemoteAddr();
        String algorithm = rateLimiter.getAlgorithm(apiKey.toLowerCase(), tenantId.toLowerCase(), appId.toLowerCase());

        //records IP for bot detection
        botDetector.record(ip);
        if (botDetector.isSuspicious(ip)) {
            wrappedResponse.setStatus(403);
            wrappedResponse.setContentType("application/json");
            wrappedResponse.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Suspicious traffic detected.\"}");
            requestLogger.log(apiKey, ip, path, "BLOCKED", "suspected_bot", algorithm, tenantId, appId, 403, 0L);
            metricsService.recordRequest(apiKey, tenantId, appId, ip, path, "BLOCKED", "suspected_bot", algorithm, 403, 0L);
            wrappedResponse.copyBodyToResponse();
            return;
        }

        //pass the wrapped response through the chain
        chain.doFilter(request, wrappedResponse);

        //now we can always read the real status code
        int status = wrappedResponse.getStatus();

        String decision;
        String reason;

        if (status == 401) {
            decision = "BLOCKED";
            reason   = "invalid_or_missing_key";
        } else if (status == 429) {
            decision = "BLOCKED";
            reason   = "rate_limit_exceeded";
        } else if (status == 403) {
            decision = "BLOCKED";
            reason   = "abuse_detected";
        } else {
            decision = "ALLOWED";
            reason   = "ok";
        }

        long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);

        //records the full request details in the log and update the metrics counters
        requestLogger.log(apiKey, ip, path, decision, reason, algorithm, tenantId, appId, status, latencyMs);
        metricsService.recordRequest(apiKey, tenantId, appId, ip, path, decision, reason, algorithm, status, latencyMs);

        //copies the response body back so the client still receives it
        //(ContentCachingResponseWrapper holds it in memory)
        wrappedResponse.copyBodyToResponse();
    }
}
