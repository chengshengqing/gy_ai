package com.zzhy.yg_ai.domain.base;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.zzhy.yg_ai.common.DateTimeUtils;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类
 * 所有实体类都应继承此类，提供公共的基础属性
 */
@Data
@EqualsAndHashCode
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    protected Long id;

    /**
     * 创建时间
     */
    protected LocalDateTime createTime;

    /**
     * 更新时间
     */
    protected LocalDateTime updateTime;

    /**
     * 创建人
     */
    protected String createBy;

    /**
     * 更新人
     */
    protected String updateBy;

    /**
     * 是否删除 (0-未删除，1-已删除)
     */
    protected Integer isDeleted;

    /**
     * 备注
     */
    protected String remark;

    /**
     * 初始化创建时间和更新时间
     */
    public void init() {
        LocalDateTime now = DateTimeUtils.now();
        this.createTime = now;
        this.updateTime = now;
    }

    /**
     * 更新更新时间
     */
    public void update() {
        this.updateTime = DateTimeUtils.now();
    }
}
