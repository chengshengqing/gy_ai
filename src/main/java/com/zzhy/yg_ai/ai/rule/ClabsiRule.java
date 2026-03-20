package com.zzhy.yg_ai.ai.rule;

import com.zzhy.yg_ai.domain.model.PatientSummary;
import com.zzhy.yg_ai.domain.model.RuleResult;
import org.springframework.stereotype.Component;

@Component
public class ClabsiRule implements InfectionRule {

    @Override
    public String getRuleCode() {
        return "CLABSI";
    }

    @Override
    public RuleResult evaluate(PatientSummary summary) {
        throw new UnsupportedOperationException("CLABSI rule is not implemented.");
    }
}
