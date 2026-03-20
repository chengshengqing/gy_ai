package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;

public class InfectionReview {

    private Long alertId;
    private String reqno;
    private boolean finalAlert;
    private Double confidence;
    private String reviewComment;
    private LocalDateTime createTime;

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public String getReqno() {
        return reqno;
    }

    public void setReqno(String reqno) {
        this.reqno = reqno;
    }

    public boolean isFinalAlert() {
        return finalAlert;
    }

    public void setFinalAlert(boolean finalAlert) {
        this.finalAlert = finalAlert;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
