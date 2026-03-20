package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 处理日志实体
 * 用于记录 AI 模型调用过程中的成功和失败信息
 */
@Data
@TableName("ai_process_log")
public class AiProcessLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 请求号
     */
    private String reqno;

    /**
     * 患者 ID
     */
    private String pathosid;

    /**
     * 原始记录 ID
     */
    private Long recordId;

    /**
     * 处理类型：STRUCT_CLASSIFICATION-结构化分类、EVENT_EXTRACTION-事件抽取等
     */
    private String processType;

    /**
     * 处理状态：SUCCESS-成功、FAILED-失败
     */
    private String status;

    /**
     * AI 返回的原始响应内容
     */
    private String aiResponse;

    /**
     * 错误码（失败时填写）
     */
    private String errorCode;

    /**
     * 错误信息（失败时填写）
     */
    private String errorMessage;

    /**
     * 额外信息（JSON 格式，用于存储扩展数据）
     */
    private String extraData;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    public void init() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update() {
        this.updatedAt = LocalDateTime.now();
    }
}
