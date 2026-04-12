package com.CS335_Project3.api_gateway.controller;

import com.CS335_Project3.api_gateway.config.RuntimeRateLimitPolicy;
import com.CS335_Project3.api_gateway.config.RuntimeRateLimitPolicyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config/rate-limit")
public class RateLimitConfigController {

    private final RuntimeRateLimitPolicyService runtimeRateLimitPolicyService;

    public RateLimitConfigController(RuntimeRateLimitPolicyService runtimeRateLimitPolicyService) {
        this.runtimeRateLimitPolicyService = runtimeRateLimitPolicyService;
    }

    @GetMapping
    public RuntimeRateLimitPolicy getConfig() {
        return runtimeRateLimitPolicyService.getPolicy();
    }

    @GetMapping("/download")
    public ResponseEntity<RuntimeRateLimitPolicy> downloadConfig() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=rate-limit-config.json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(runtimeRateLimitPolicyService.getPolicy());
    }

    @PostMapping
    public RuntimeRateLimitPolicy uploadConfig(@RequestBody RuntimeRateLimitPolicy payload) {
        return runtimeRateLimitPolicyService.savePolicy(payload);
    }
}
