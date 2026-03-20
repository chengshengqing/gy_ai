package com.zzhy.yg_ai.domain.base;

import lombok.Data;

import java.io.Serializable;

/**
 * DTO 基类
 * 所有 DTO 类都应继承此类，提供基础的数据传输功能
 */
@Data
public abstract class BaseDTO implements Serializable {

    private static final long serialVersionUID = 1L;
}
