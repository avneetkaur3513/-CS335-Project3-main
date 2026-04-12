package com.CS335_Project3.api_gateway.config;

import java.util.HashMap;
import java.util.Map;

public class RuntimeRateLimitPolicy {
    private int defaultLimit = 5;
    private String defaultAlgorithm = "token";
    private Map<String, ClientPolicy> clients = new HashMap<>();
    private Map<String, TenantPolicy> tenants = new HashMap<>();

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public String getDefaultAlgorithm() {
        return defaultAlgorithm;
    }

    public void setDefaultAlgorithm(String defaultAlgorithm) {
        this.defaultAlgorithm = defaultAlgorithm;
    }

    public Map<String, ClientPolicy> getClients() {
        return clients;
    }

    public void setClients(Map<String, ClientPolicy> clients) {
        this.clients = clients;
    }

    public Map<String, TenantPolicy> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantPolicy> tenants) {
        this.tenants = tenants;
    }

    public static class ClientPolicy {
        private int limit = 5;
        private String algorithm = "token";
        private boolean enabled = true;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class TenantPolicy {
        private int limit = 5;
        private String algorithm = "token";
        private boolean enabled = true;
        private Map<String, AppPolicy> apps = new HashMap<>();

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, AppPolicy> getApps() {
            return apps;
        }

        public void setApps(Map<String, AppPolicy> apps) {
            this.apps = apps;
        }
    }

    public static class AppPolicy {
        private int limit = 5;
        private String algorithm;
        private boolean enabled = true;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
