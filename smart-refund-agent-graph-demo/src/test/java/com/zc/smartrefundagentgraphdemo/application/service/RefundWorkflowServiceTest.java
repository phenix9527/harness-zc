package com.zc.smartrefundagentgraphdemo.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.zc.smartrefundagentgraphdemo.application.model.RefundPlan;
import com.zc.smartrefundagentgraphdemo.application.model.RefundPlanRequest;
import com.zc.smartrefundagentgraphdemo.application.port.RefundWorkflowPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundWorkflowServiceTest {

    private final RefundWorkflowPort refundWorkflowPort = org.mockito.Mockito.mock(RefundWorkflowPort.class);
    private final RefundWorkflowService service = new RefundWorkflowService(refundWorkflowPort);

    @Test
    void buildsPlanThroughPort() {
        RefundPlanRequest request = new RefundPlanRequest("order-1", "customer requested", "wechat");
        RefundPlan plan = new RefundPlan("ok", List.of("step-1", "step-2"));
        when(refundWorkflowPort.buildPlan(request)).thenReturn(plan);

        RefundPlan result = service.buildPlan(request);

        assertThat(result).isEqualTo(plan);
    }

    @Test
    void rejectsBlankOrderId() {
        assertThatThrownBy(() -> service.buildPlan(new RefundPlanRequest(" ", "reason", "wechat")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderId must not be blank");
    }
}
