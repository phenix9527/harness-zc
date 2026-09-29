package com.zc.smartrefundagentgraphdemo.infrastructure.langgraph;

import com.zc.smartrefundagentgraphdemo.application.model.RefundPlan;
import com.zc.smartrefundagentgraphdemo.application.model.RefundPlanRequest;
import com.zc.smartrefundagentgraphdemo.application.port.RefundWorkflowPort;
import org.springframework.stereotype.Component;

@Component
public class LocalLangGraphRefundWorkflowAdapter implements RefundWorkflowPort {

    @Override
    public RefundPlan buildPlan(RefundPlanRequest request) {
        return new RefundPlan(
                "Local scaffold plan for refund workflow",
                java.util.List.of(
                        "collect order facts",
                        "check refund policy",
                        "prepare approval draft",
                        "handoff to next agent node"));
    }
}
