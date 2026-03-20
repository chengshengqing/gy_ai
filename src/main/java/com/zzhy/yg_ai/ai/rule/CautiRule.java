package com.zzhy.yg_ai.ai.rule;

import com.zzhy.yg_ai.domain.model.PatientSummary;
import com.zzhy.yg_ai.domain.model.RuleResult;
import org.springframework.stereotype.Component;

@Component
public class CautiRule implements InfectionRule {

    @Override
    public String getRuleCode() {
        return "CAUTI";
    }

    @Override
    public RuleResult evaluate(PatientSummary summary) {
        throw new UnsupportedOperationException("CAUTI rule is not implemented.");
    }
}
