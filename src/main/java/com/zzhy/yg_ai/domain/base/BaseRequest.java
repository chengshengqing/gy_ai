package com.zzhy.yg_ai.domain.base;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 请求基类
 * 所有请求类都应继承此类，提供通用的请求参数和验证方法
 */
@Data
@EqualsAndHashCode
public abstract class BaseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 请求 ID (可用于日志追踪、幂等性校验等)
     */
    protected String requestId;

    /**
     * 请求时间戳
     */
    protected Long timestamp;

    /**
     * 请求来源
     */
    protected String source;

    /**
     * 页码 (分页查询时使用，默认第 1 页)
     */
    protected Integer pageNum = 1;

    /**
     * 每页数量 (分页查询时使用，默认 10 条)
     */
    protected Integer pageSize = 10;

    /**
     * 初始化请求信息
     */
    public void initRequest() {
        if (this.timestamp == null) {
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * 验证请求参数的合法性
     * 子类可以重写此方法进行具体的参数校验
     * @return true-合法，false-非法
     */
    public boolean validate() {
        return true;
    }

    /**
     * 获取验证失败的消息
     * @return 验证失败消息
     */
    public String getValidateMessage() {
        return "请求参数验证失败";
    }
}
