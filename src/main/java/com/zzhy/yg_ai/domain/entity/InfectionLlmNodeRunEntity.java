package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 院感 LLM 节点运行留痕。
 * 所有预警链路模型调用后续统一落在该表。
 */
@Data
@TableName("infection_llm_node_run")
public class InfectionLlmNodeRunEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String reqno;
    private Long rawDataId;
    private Long alertResultId;
    private String nodeRunKey;
    private String nodeType;
    private String nodeName;
    private String promptVersion;
    private String modelName;
    private String inputPayload;
    private String outputPayload;
    private String normalizedOutputPayload;
    private String status;
    private BigDecimal confidence;
    private Long latencyMs;
    private Integer retryCount;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void initPending() {
        LocalDateTime now = LocalDateTime.now();
        this.status = this.status == null ? "PENDING" : this.status;
        this.retryCount = this.retryCount == null ? 0 : this.retryCount;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
