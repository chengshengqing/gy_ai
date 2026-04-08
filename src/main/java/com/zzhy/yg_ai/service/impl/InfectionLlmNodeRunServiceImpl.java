package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.InfectionNodeRunStatus;
import com.zzhy.yg_ai.mapper.InfectionLlmNodeRunMapper;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class InfectionLlmNodeRunServiceImpl
        extends ServiceImpl<InfectionLlmNodeRunMapper, InfectionLlmNodeRunEntity>
        implements InfectionLlmNodeRunService {

    @Override
    public InfectionLlmNodeRunEntity createPendingRun(InfectionLlmNodeRunEntity entity) {
        entity.initPending();
        this.save(entity);
        return entity;
    }

    @Override
    public void markSuccess(Long id,
                            String outputPayload,
                            String normalizedOutputPayload,
                            BigDecimal confidence,
                            Long latencyMs) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setId(id);
        entity.setStatus(InfectionNodeRunStatus.SUCCESS.name());
        entity.setOutputPayload(outputPayload);
        entity.setNormalizedOutputPayload(normalizedOutputPayload);
        entity.setConfidence(confidence);
        entity.setLatencyMs(latencyMs);
        entity.touch();
        this.updateById(entity);
    }

    @Override
    public void markFailed(Long id,
                           String outputPayload,
                           String normalizedOutputPayload,
                           String errorCode,
                           String errorMessage,
                           Long latencyMs) {
        InfectionLlmNodeRunEntity entity = new InfectionLlmNodeRunEntity();
        entity.setId(id);
        entity.setStatus(InfectionNodeRunStatus.FAILED.name());
        entity.setOutputPayload(outputPayload);
        entity.setNormalizedOutputPayload(normalizedOutputPayload);
        entity.setErrorCode(errorCode);
        entity.setErrorMessage(errorMessage);
        entity.setLatencyMs(latencyMs);
        entity.touch();
        this.updateById(entity);
    }
}
