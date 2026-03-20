package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 感染审核实体
 */
@TableName("infection_review")
public class InfectionReviewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 报警ID
     */
    private Long alertId;
    /**
     * 患者请求号
     */
    private String reqno;
    /**
     * 是否最终报警
     */
    private Boolean finalAlert;
    /**
     * 置信度
     */
    private Double confidence;
    /**
     * 审核评论
     */
    private String reviewComment;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Boolean getFinalAlert() {
        return finalAlert;
    }

    public void setFinalAlert(Boolean finalAlert) {
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
