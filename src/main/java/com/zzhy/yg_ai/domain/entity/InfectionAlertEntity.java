package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 感染报警实体
 */
@TableName("infection_alert")
public class InfectionAlertEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 患者请求号
     */
    private String reqno;
    /**
     * 危险等级
     */
    private String riskLevel;
    /**
     * 感染类型
     */
    private String infectionType;
    /**
     * 证据
     */
    private String evidence;
    /**
     * 报警时间
     */
    private LocalDateTime alertTime;
    /**
     * 状态
     */
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReqno() {
        return reqno;
    }

    public void setReqno(String reqno) {
        this.reqno = reqno;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getInfectionType() {
        return infectionType;
    }

    public void setInfectionType(String infectionType) {
        this.infectionType = infectionType;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
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
