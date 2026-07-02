package com.zc.smartrefundagentgraphdemo.application.model;

public record RefundPlanRequest(
        String orderId,
        String reason,
        String channel) {
}
