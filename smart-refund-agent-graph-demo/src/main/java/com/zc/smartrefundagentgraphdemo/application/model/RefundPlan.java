package com.zc.smartrefundagentgraphdemo.application.model;

import java.util.List;

public record RefundPlan(
        String summary,
        List<String> steps) {

    public RefundPlan {
        steps = List.copyOf(steps);
    }
}
