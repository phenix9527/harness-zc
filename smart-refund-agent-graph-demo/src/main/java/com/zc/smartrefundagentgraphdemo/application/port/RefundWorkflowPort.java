package com.zc.smartrefundagentgraphdemo.application.port;

import com.zc.smartrefundagentgraphdemo.application.model.RefundPlan;
import com.zc.smartrefundagentgraphdemo.application.model.RefundPlanRequest;

public interface RefundWorkflowPort {

    RefundPlan buildPlan(RefundPlanRequest request);
}
