package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;

public class InfectionAlert {

    private String reqno;
    private InfectionType infectionType;
    private RiskLevel riskLevel;
    private String evidence;
    private String ruleCode;
    private LocalDateTime alertTime;
    private String status;

    public String getReqno() {
        return reqno;
    }

    public void setReqno(String reqno) {
        this.reqno = reqno;
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

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public LocalDateTime getAlertTime() {
        return alertTime;
    }

    public void setAlertTime(LocalDateTime alertTime) {
        this.alertTime = alertTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
