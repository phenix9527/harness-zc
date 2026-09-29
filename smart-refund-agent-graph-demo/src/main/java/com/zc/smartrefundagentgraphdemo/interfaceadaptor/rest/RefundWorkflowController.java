package com.zc.smartrefundagentgraphdemo.interfaceadaptor.rest;

import com.zc.smartrefundagentgraphdemo.application.model.RefundPlan;
import com.zc.smartrefundagentgraphdemo.application.model.RefundPlanRequest;
import com.zc.smartrefundagentgraphdemo.application.service.RefundWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/refunds")
public class RefundWorkflowController {

    private final RefundWorkflowService refundWorkflowService;

    public RefundWorkflowController(RefundWorkflowService refundWorkflowService) {
        this.refundWorkflowService = refundWorkflowService;
    }

    @PostMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public RefundPlanResponse buildPlan(@Valid @RequestBody RefundPlanRequestBody body) {
        RefundPlan plan = refundWorkflowService.buildPlan(new RefundPlanRequest(
                body.orderId(),
                body.reason(),
                body.channel()));
        return new RefundPlanResponse(plan.summary(), plan.steps());
    }

    public record RefundPlanRequestBody(
            @NotBlank String orderId,
            @NotBlank String reason,
            @NotBlank String channel) {
    }

    public record RefundPlanResponse(
            String summary,
            java.util.List<String> steps) {
    }
}
