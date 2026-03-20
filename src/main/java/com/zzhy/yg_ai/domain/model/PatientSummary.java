package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;

public class PatientSummary {

    private String reqno;
    private String summaryJson;
    private Integer tokenCount;
    private LocalDateTime updatedAt;

    public String getReqno() {
        return reqno;
    }

    public void setReqno(String reqno) {
        this.reqno = reqno;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
