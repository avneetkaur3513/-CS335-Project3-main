package com.CS335_Project3.api_gateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RuntimeRateLimitPolicyService {

    private static final String POLICY_KEY = "gateway:runtime:rate-limit-policy:v1";

    private final StringRedisTemplate redisTemplate;
    private final TenantRateLimitConfig tenantRateLimitConfig;
    private final ObjectMapper objectMapper;

    private final AtomicReference<RuntimeRateLimitPolicy> currentPolicy = new AtomicReference<>(new RuntimeRateLimitPolicy());

    public RuntimeRateLimitPolicyService(StringRedisTemplate redisTemplate,
                                         TenantRateLimitConfig tenantRateLimitConfig,
                                         ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.tenantRateLimitConfig = tenantRateLimitConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        String raw = redisTemplate.opsForValue().get(POLICY_KEY);
        if (raw == null || raw.isBlank()) {
            RuntimeRateLimitPolicy seeded = buildInitialPolicy();
            savePolicy(seeded);
            currentPolicy.set(seeded);
            return;
        }
        currentPolicy.set(parse(raw));
    }

    public RuntimeRateLimitPolicy getPolicy() {
        return currentPolicy.get();
    }

    public RuntimeRateLimitPolicy savePolicy(RuntimeRateLimitPolicy newPolicy) {
        RuntimeRateLimitPolicy normalized = normalize(newPolicy);
        try {
            redisTemplate.opsForValue().set(POLICY_KEY, objectMapper.writeValueAsString(normalized));
            currentPolicy.set(normalized);
            return normalized;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid policy JSON", e);
        }
    }

    private RuntimeRateLimitPolicy parse(String raw) {
        try {
            RuntimeRateLimitPolicy parsed = objectMapper.readValue(raw, RuntimeRateLimitPolicy.class);
            return normalize(parsed);
        } catch (JsonProcessingException e) {
            return normalize(buildInitialPolicy());
        }
    }

    private RuntimeRateLimitPolicy normalize(RuntimeRateLimitPolicy policy) {
        RuntimeRateLimitPolicy out = policy == null ? new RuntimeRateLimitPolicy() : policy;
        if (out.getDefaultLimit() <= 0) {
            out.setDefaultLimit(5);
        }
        if (out.getDefaultAlgorithm() == null || out.getDefaultAlgorithm().isBlank()) {
            out.setDefaultAlgorithm("token");
        } else {
            out.setDefaultAlgorithm(out.getDefaultAlgorithm().toLowerCase());
        }
        Map<String, RuntimeRateLimitPolicy.ClientPolicy> normalizedClients = new HashMap<>();
        if (out.getClients() != null) {
            for (Map.Entry<String, RuntimeRateLimitPolicy.ClientPolicy> entry : out.getClients().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                RuntimeRateLimitPolicy.ClientPolicy p = entry.getValue();
                if (p.getLimit() <= 0) {
                    p.setLimit(5);
                }
                p.setAlgorithm((p.getAlgorithm() == null || p.getAlgorithm().isBlank()) ? "token" : p.getAlgorithm().toLowerCase());
                normalizedClients.put(entry.getKey().toLowerCase(), p);
            }
        }
        out.setClients(normalizedClients);

        Map<String, RuntimeRateLimitPolicy.TenantPolicy> normalizedTenants = new HashMap<>();
        if (out.getTenants() != null) {
            for (Map.Entry<String, RuntimeRateLimitPolicy.TenantPolicy> entry : out.getTenants().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                RuntimeRateLimitPolicy.TenantPolicy tenant = entry.getValue();
                if (tenant.getLimit() <= 0) {
                    tenant.setLimit(5);
                }
                tenant.setAlgorithm((tenant.getAlgorithm() == null || tenant.getAlgorithm().isBlank()) ? "token" : tenant.getAlgorithm().toLowerCase());
                Map<String, RuntimeRateLimitPolicy.AppPolicy> normalizedApps = new HashMap<>();
                if (tenant.getApps() != null) {
                    for (Map.Entry<String, RuntimeRateLimitPolicy.AppPolicy> appEntry : tenant.getApps().entrySet()) {
                        if (appEntry.getKey() == null || appEntry.getValue() == null) {
                            continue;
                        }
                        RuntimeRateLimitPolicy.AppPolicy app = appEntry.getValue();
                        if (app.getLimit() <= 0) {
                            app.setLimit(5);
                        }
                        if (app.getAlgorithm() != null && !app.getAlgorithm().isBlank()) {
                            app.setAlgorithm(app.getAlgorithm().toLowerCase());
                        }
                        normalizedApps.put(appEntry.getKey().toLowerCase(), app);
                    }
                }
                tenant.setApps(normalizedApps);
                normalizedTenants.put(entry.getKey().toLowerCase(), tenant);
            }
        }
        out.setTenants(normalizedTenants);
        return out;
    }

    private RuntimeRateLimitPolicy buildInitialPolicy() {
        RuntimeRateLimitPolicy policy = new RuntimeRateLimitPolicy();
        policy.setDefaultLimit(tenantRateLimitConfig.getDefaultLimit());
        policy.setDefaultAlgorithm(tenantRateLimitConfig.getDefaultAlgorithm());

        RuntimeRateLimitPolicy.ClientPolicy token = new RuntimeRateLimitPolicy.ClientPolicy();
        token.setAlgorithm("token");
        token.setLimit(3);
        policy.getClients().put("dev-key-token", token);

        RuntimeRateLimitPolicy.ClientPolicy fixed = new RuntimeRateLimitPolicy.ClientPolicy();
        fixed.setAlgorithm("fixed");
        fixed.setLimit(3);
        policy.getClients().put("dev-key-fixed", fixed);

        RuntimeRateLimitPolicy.ClientPolicy sliding = new RuntimeRateLimitPolicy.ClientPolicy();
        sliding.setAlgorithm("sliding");
        sliding.setLimit(3);
        policy.getClients().put("dev-key-sliding", sliding);

        RuntimeRateLimitPolicy.ClientPolicy business = new RuntimeRateLimitPolicy.ClientPolicy();
        business.setAlgorithm("token");
        business.setLimit(6);
        policy.getClients().put("dev-key-business", business);

        for (Map.Entry<String, TenantRateLimitConfig.TenantPolicy> tenantEntry : tenantRateLimitConfig.getTenants().entrySet()) {
            RuntimeRateLimitPolicy.TenantPolicy tenant = new RuntimeRateLimitPolicy.TenantPolicy();
            tenant.setEnabled(tenantEntry.getValue().isEnabled());
            tenant.setLimit(tenantEntry.getValue().getLimit());
            tenant.setAlgorithm(tenantEntry.getValue().getAlgorithm());
            for (Map.Entry<String, TenantRateLimitConfig.AppPolicy> appEntry : tenantEntry.getValue().getApps().entrySet()) {
                RuntimeRateLimitPolicy.AppPolicy app = new RuntimeRateLimitPolicy.AppPolicy();
                app.setEnabled(appEntry.getValue().isEnabled());
                app.setLimit(appEntry.getValue().getLimit());
                tenant.getApps().put(appEntry.getKey().toLowerCase(), app);
            }
            policy.getTenants().put(tenantEntry.getKey().toLowerCase(), tenant);
        }
        return policy;
    }
}
