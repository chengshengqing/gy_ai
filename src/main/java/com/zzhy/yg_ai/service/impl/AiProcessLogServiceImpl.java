package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.JsonUtil;
import com.zzhy.yg_ai.domain.entity.AiProcessLog;
import com.zzhy.yg_ai.mapper.AiProcessLogMapper;
import com.zzhy.yg_ai.service.AiProcessLogService;
import org.springframework.stereotype.Service;

/**
 * AI 处理日志服务实现类
 */
@Service
public class AiProcessLogServiceImpl extends ServiceImpl<AiProcessLogMapper, AiProcessLog> implements AiProcessLogService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void logSuccess(String reqno, String pathosid, Long recordId, String processType, 
                           String aiResponse, String extraData) {
        AiProcessLog log = new AiProcessLog();
        log.setReqno(reqno);
        log.setPathosid(pathosid);
        log.setRecordId(recordId);
        log.setProcessType(processType);
        log.setStatus("SUCCESS");
        log.setAiResponse(aiResponse);
        log.setExtraData(extraData);
        log.init();
        this.save(log);
    }

    @Override
    public void logFailed(String reqno, String pathosid, Long recordId, String processType,
                          String aiResponse, String errorCode, String errorMessage, String extraData) {
        AiProcessLog log = new AiProcessLog();
        log.setReqno(reqno);
        log.setPathosid(pathosid);
        log.setRecordId(recordId);
        log.setProcessType(processType);
        log.setStatus("FAILED");
        log.setAiResponse(aiResponse);
        log.setErrorCode(errorCode);
        log.setErrorMessage(errorMessage);
        log.setExtraData(extraData);
        log.init();
        this.save(log);
    }

    @Override
    public Page<AiProcessLog> getPage(Integer pageNum, Integer pageSize) {
        Page<AiProcessLog> page = new Page<>(pageNum, pageSize);
        return this.page(page);
    }
}
