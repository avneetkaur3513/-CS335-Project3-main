package com.CS335_Project3.api_gateway;

import org.springframework.stereotype.Component;
import java.util.Map;
import com.CS335_Project3.api_gateway.config.TenantRateLimitConfig;
import com.CS335_Project3.api_gateway.config.RuntimeRateLimitPolicy;
import com.CS335_Project3.api_gateway.config.RuntimeRateLimitPolicyService;
import com.CS335_Project3.api_gateway.ratelimiter.RateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.TokenBucketRateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.FixedWindowRateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.SlidingWindowRateLimiterStrategy;
import com.CS335_Project3.api_gateway.ratelimiter.LeakyBucketRateLimiterStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashMap;

/**
 * Main entry point for rate limiting logic.
 * Manages multiple strategies and resolves 
 * hierarchical policies (App > Tenant > Global).
 */
@Component
public class RateLimiter {

    /*
        This class acts as the main entry point for rate limiting.

        It holds multiple rate limiting strategies and chooses
        which one to use based on the client.

        This allows different clients to use different algorithms.
    */

    // Strategy instances
    private final TokenBucketRateLimiterStrategy tokenBucketStrategy;
    private final FixedWindowRateLimiterStrategy fixedWindowStrategy;
    private final SlidingWindowRateLimiterStrategy slidingWindowStrategy;
    private final LeakyBucketRateLimiterStrategy leakyBucketStrategy;

    // Config for hierarchical policies
    private final TenantRateLimitConfig tenantRateLimitConfig;
    private final RuntimeRateLimitPolicyService runtimeRateLimitPolicyService;

    /*
        This map stores which algorithm each client should use

        Key   = clientId (API key)
        Value = algorithm name
    */
    private final Map<String, String> fallbackClientAlgorithms = new HashMap<>();

    /*
        This map stores the actual strategies

        Key   = algorithm name
        Value = strategy implementation

        This removes the need for switch statements
    */
    private final Map<String, RateLimiterStrategy> strategies = new HashMap<>();

    /*
        This map stores each client's request limit / bucket size

        Key   = clientId (API key)
        Value = max allowed requests / capacity
    */
    private final Map<String, Integer> fallbackClientLimits = new HashMap<>();

    /*
        Primary constructor used by Spring (dependency injection)

        Spring injects each strategy here, including the Redis-backed
        fixed window strategy.

        This is now the only constructor needed.
    */
    @Autowired
    public RateLimiter(TokenBucketRateLimiterStrategy tokenBucketStrategy,
                       FixedWindowRateLimiterStrategy fixedWindowStrategy,
                       SlidingWindowRateLimiterStrategy slidingWindowStrategy,
                       LeakyBucketRateLimiterStrategy leakyBucketRateLimiterStrategy,
                       TenantRateLimitConfig tenantRateLimitConfig,
                       RuntimeRateLimitPolicyService runtimeRateLimitPolicyService) {

        this.tokenBucketStrategy = tokenBucketStrategy;
        this.fixedWindowStrategy = fixedWindowStrategy;
        this.slidingWindowStrategy = slidingWindowStrategy;
        this.leakyBucketStrategy = leakyBucketRateLimiterStrategy;
        this.tenantRateLimitConfig = tenantRateLimitConfig;
        this.runtimeRateLimitPolicyService = runtimeRateLimitPolicyService;

        registerStrategies();
        registerClientPolicies();
    }

    /*
        Register all available rate limiting strategies
    */
    private void registerStrategies() {
        strategies.put("token", tokenBucketStrategy);
        strategies.put("fixed", fixedWindowStrategy);
        strategies.put("sliding", slidingWindowStrategy);
        strategies.put("leaky", leakyBucketStrategy);
    }

    /*
        Assign algorithms and limits to clients
    */
    private void registerClientPolicies() {
        // Standard clients
        fallbackClientAlgorithms.put("dev-key-token", "token");
        fallbackClientAlgorithms.put("dev-key-fixed", "fixed");
        fallbackClientAlgorithms.put("dev-key-sliding", "sliding");

        // limit lowered from 5 to 3 for testing purposes
        // to trigger 429 without sending too many requests for logging
        fallbackClientLimits.put("dev-key-token", 3);
        fallbackClientLimits.put("dev-key-fixed", 3);
        fallbackClientLimits.put("dev-key-sliding", 3);

        // Business client
        fallbackClientAlgorithms.put("dev-key-business", "token");

        // limit also lowered from 10 to 6 for testing purposes
        // to trigger 429 without sending too many requests for logging
        fallbackClientLimits.put("dev-key-business", 6);
    }

    /*
        Called by API key filter

        Determines which algorithm to use for the client
        and delegates the request to that strategy
    */
    public boolean isRequestAllowed(String clientId) {
        return isRequestAllowed(clientId, "default", "default");
    }

    // returns which rate limiting algorithm is assigned to the given client in the logs
    // it defaults to "token" algorithm if the client is not found in the map
    public String getAlgorithm(String clientId) {
        return resolvePolicy(clientId, "default", "default").algorithm();
    }

    public String getAlgorithm(String clientId, String tenantId, String appId) {
        return resolvePolicy(clientId, tenantId, appId).algorithm();
    }

    /**
     * New overloaded method for hierarchical scoping.
     * Resolves limits in order: App > Tenant > Global Default.
     */
    public boolean isRequestAllowed(String clientId, String tenantId, String appId) {
        PolicyResolution policy = resolvePolicy(clientId, tenantId, appId);
        if (!policy.enabled()) {
            return true;
        }

        RateLimiterStrategy strategy = strategies.getOrDefault(policy.algorithm(), tokenBucketStrategy);
        String normalizedTenant = normalize(tenantId, "default");
        String normalizedApp = normalize(appId, "default");
        String normalizedClient = normalize(clientId, "unknown");
        String bucketKey = normalizedTenant + "/" + normalizedApp + "/" + normalizedClient;

        return strategy.isRequestAllowed(bucketKey, policy.limit());
    }

    private PolicyResolution resolvePolicy(String clientId, String tenantId, String appId) {
        String normalizedClient = normalize(clientId, "unknown");
        String normalizedTenant = normalize(tenantId, "default");
        String normalizedApp = normalize(appId, "default");

        RuntimeRateLimitPolicy runtimePolicy = runtimeRateLimitPolicyService.getPolicy();
        if (runtimePolicy == null) {
            return fallbackPolicy(normalizedClient, normalizedTenant);
        }

        RuntimeRateLimitPolicy.ClientPolicy clientPolicy = runtimePolicy.getClients().get(normalizedClient);
        RuntimeRateLimitPolicy.TenantPolicy tenantPolicy = runtimePolicy.getTenants().get(normalizedTenant);
        RuntimeRateLimitPolicy.AppPolicy appPolicy = tenantPolicy == null ? null : tenantPolicy.getApps().get(normalizedApp);

        if (tenantPolicy != null && !tenantPolicy.isEnabled()) {
            return new PolicyResolution(1, runtimePolicy.getDefaultAlgorithm(), false);
        }
        if (appPolicy != null && !appPolicy.isEnabled()) {
            appPolicy = null;
        }
        if (clientPolicy != null && !clientPolicy.isEnabled()) {
            return new PolicyResolution(1, runtimePolicy.getDefaultAlgorithm(), false);
        }

        int limit = runtimePolicy.getDefaultLimit();
        String algorithm = runtimePolicy.getDefaultAlgorithm();

        if (clientPolicy != null) {
            limit = clientPolicy.getLimit();
            algorithm = normalize(clientPolicy.getAlgorithm(), algorithm);
        }
        if (tenantPolicy != null) {
            limit = tenantPolicy.getLimit();
            algorithm = normalize(tenantPolicy.getAlgorithm(), algorithm);
        }
        if (appPolicy != null) {
            limit = appPolicy.getLimit();
            algorithm = normalize(appPolicy.getAlgorithm(), algorithm);
        }

        return new PolicyResolution(Math.max(limit, 1), normalize(algorithm, "token"), true);
    }

    private PolicyResolution fallbackPolicy(String clientId, String tenantId) {
        TenantRateLimitConfig cfg = this.tenantRateLimitConfig;
        TenantRateLimitConfig.TenantPolicy tenantPolicy = (cfg != null && tenantId != null)
                ? cfg.getTenants().get(tenantId) : null;
        if (tenantPolicy != null && !tenantPolicy.isEnabled()) {
            return new PolicyResolution(1, "token", false);
        }
        int limit = fallbackClientLimits.getOrDefault(clientId, (cfg != null) ? cfg.getDefaultLimit() : 5);
        String algo = fallbackClientAlgorithms.getOrDefault(clientId, (cfg != null) ? cfg.getDefaultAlgorithm() : "token");
        if (tenantPolicy != null) {
            limit = tenantPolicy.getLimit();
            algo = tenantPolicy.getAlgorithm();
        }
        return new PolicyResolution(Math.max(limit, 1), normalize(algo, "token"), true);
    }

    private String normalize(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value.toLowerCase();
    }

    private record PolicyResolution(int limit, String algorithm, boolean enabled) { }

    public int getEffectiveLimit(String clientId, String tenantId, String appId) {
        return resolvePolicy(clientId, tenantId, appId).limit();
    }
}
