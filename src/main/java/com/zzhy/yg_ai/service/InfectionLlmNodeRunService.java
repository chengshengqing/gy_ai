package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import java.math.BigDecimal;

public interface InfectionLlmNodeRunService extends IService<InfectionLlmNodeRunEntity> {

    InfectionLlmNodeRunEntity createPendingRun(InfectionLlmNodeRunEntity entity);

    void markSuccess(Long id,
                     String outputPayload,
                     String normalizedOutputPayload,
                     BigDecimal confidence,
                     Long latencyMs);

    void markFailed(Long id,
                    String outputPayload,
                    String errorCode,
                    String errorMessage,
                    Long latencyMs);
}
