package com.zzhy.yg_ai.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.zzhy.yg_ai.domain.model.InfectionAlert;
import com.zzhy.yg_ai.domain.model.InfectionReview;
import com.zzhy.yg_ai.domain.model.PatientSummary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AuditAgent {

    private final ReactAgent reactAgent;

    public AuditAgent(@Qualifier("auditReactAgent") ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    public InfectionReview audit(InfectionAlert alert, PatientSummary summary) {
        throw new UnsupportedOperationException("AuditAgent is not implemented.");
    }

    public ReactAgent getReactAgent() {
        return reactAgent;
    }
}
