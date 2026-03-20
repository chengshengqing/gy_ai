package com.zzhy.yg_ai.ai.rule;

import com.zzhy.yg_ai.domain.model.PatientSummary;
import com.zzhy.yg_ai.domain.model.RuleResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleEngine {

    private final List<InfectionRule> rules;

    public RuleEngine(List<InfectionRule> rules) {
        this.rules = rules;
    }

    public RuleResult evaluate(PatientSummary summary) {
        throw new UnsupportedOperationException("RuleEngine evaluation is not implemented.");
    }
}
