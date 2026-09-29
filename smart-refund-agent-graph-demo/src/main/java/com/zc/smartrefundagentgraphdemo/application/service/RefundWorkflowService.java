package com.zc.smartrefundagentgraphdemo.application.service;

import com.zc.smartrefundagentgraphdemo.application.model.RefundPlan;
import com.zc.smartrefundagentgraphdemo.application.model.RefundPlanRequest;
import com.zc.smartrefundagentgraphdemo.application.port.RefundWorkflowPort;
import org.springframework.stereotype.Service;

@Service
public class RefundWorkflowService {

    private final RefundWorkflowPort refundWorkflowPort;

    public RefundWorkflowService(RefundWorkflowPort refundWorkflowPort) {
        this.refundWorkflowPort = refundWorkflowPort;
    }

    public RefundPlan buildPlan(RefundPlanRequest request) {
        validate(request);
        return refundWorkflowPort.buildPlan(request);
    }

    private void validate(RefundPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.orderId() == null || request.orderId().isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (request.channel() == null || request.channel().isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
    }
}
