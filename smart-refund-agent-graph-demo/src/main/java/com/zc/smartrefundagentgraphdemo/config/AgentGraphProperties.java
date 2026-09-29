package com.zc.smartrefundagentgraphdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smart.refund.agent-graph")
public record AgentGraphProperties(
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        int timeoutSeconds) {

    public AgentGraphProperties {
        provider = provider == null ? "local" : provider;
        model = model == null ? "stub" : model;
        baseUrl = baseUrl == null ? "" : baseUrl;
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        apiKey = apiKey == null ? "" : apiKey;
    }
}
