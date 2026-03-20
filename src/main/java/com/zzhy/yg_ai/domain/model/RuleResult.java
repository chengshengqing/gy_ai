package com.zzhy.yg_ai.domain.model;

public class RuleResult {

    private boolean risk;
    private String ruleCode;
    private InfectionType infectionType;
    private RiskLevel riskLevel;
    private String evidence;
    private String message;

    public boolean isRisk() {
        return risk;
    }

    public void setRisk(boolean risk) {
        this.risk = risk;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public InfectionType getInfectionType() {
        return infectionType;
    }

    public void setInfectionType(InfectionType infectionType) {
        this.infectionType = infectionType;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
