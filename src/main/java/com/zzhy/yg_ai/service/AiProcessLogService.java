package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.AiProcessLog;

/**
 * AI 处理日志服务接口
 */
public interface AiProcessLogService extends IService<AiProcessLog> {

    /**
     * 记录成功的 AI 处理日志
     *
     * @param reqno 请求号
     * @param pathosid 患者 ID
     * @param recordId 原始记录 ID
     * @param processType 处理类型
     * @param aiResponse AI 返回的响应内容
     * @param extraData 额外信息
     */
    void logSuccess(String reqno, String pathosid, Long recordId, String processType, 
                    String aiResponse, String extraData);

    /**
     * 记录失败的 AI 处理日志
     *
     * @param reqno 请求号
     * @param pathosid 患者 ID
     * @param recordId 原始记录 ID
     * @param processType 处理类型
     * @param aiResponse AI 返回的响应内容（可能为 null 或无效数据）
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     * @param extraData 额外信息
     */
    void logFailed(String reqno, String pathosid, Long recordId, String processType,
                   String aiResponse, String errorCode, String errorMessage, String extraData);

    /**
     * 分页查询日志
     *
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    Page<AiProcessLog> getPage(Integer pageNum, Integer pageSize);
}
