package com.zzhy.yg_ai.domain.base;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 响应结果基类
 * 所有响应类都应继承此类，提供通用的响应字段
 * 
 * @param <T> 响应数据类型
 */
@Data
@EqualsAndHashCode
public class BaseResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 状态码 (200-成功，其他 - 失败)
     */
    protected Integer code;

    /**
     * 响应消息
     */
    protected String message;

    /**
     * 实际数据
     */
    protected T data;

    /**
     * 响应时间戳
     */
    protected Long timestamp;

    /**
     * 请求 ID (用于日志追踪)
     */
    protected String requestId;

    /**
     * 构造函数
     */
    public BaseResult() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构造成功的响应
     * @param data 数据
     */
    public BaseResult(T data) {
        this.code = 200;
        this.message = "success";
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构造指定状态码和消息的响应
     * @param code 状态码
     * @param message 消息
     */
    public BaseResult(Integer code, String message) {
        this.code = code;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构造指定状态码、消息和数据的响应
     * @param code 状态码
     * @param message 消息
     * @param data 数据
     */
    public BaseResult(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 判断是否成功
     * @return true-成功，false-失败
     */
    public boolean isSuccess() {
        return this.code != null && this.code == 200;
    }

    /**
     * 设置成功状态
     */
    public void setSuccess() {
        this.code = 200;
        this.message = "success";
    }

    /**
     * 设置失败状态
     * @param message 失败消息
     */
    public void setError(String message) {
        this.code = 500;
        this.message = message;
    }

    /**
     * 设置失败状态
     * @param code 错误码
     * @param message 错误消息
     */
    public void setError(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
