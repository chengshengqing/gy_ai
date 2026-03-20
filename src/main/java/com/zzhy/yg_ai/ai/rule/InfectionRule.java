package com.zzhy.yg_ai.ai.rule;

import com.zzhy.yg_ai.domain.model.PatientSummary;
import com.zzhy.yg_ai.domain.model.RuleResult;

public interface InfectionRule {

    String getRuleCode();

    RuleResult evaluate(PatientSummary summary);
}
