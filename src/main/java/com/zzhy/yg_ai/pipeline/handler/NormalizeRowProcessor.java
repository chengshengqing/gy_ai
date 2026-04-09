package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.normalize.facts.DailyIllnessExtractionResult;
import com.zzhy.yg_ai.service.PatientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizeRowProcessor {

    private static final String RETRY_MESSAGE = "存在未完成的 rawData 行，需重试";

    private final NormalizeStructDataComposer normalizeStructDataComposer;
    private final PatientService patientService;

    public NormalizeRowProcessResult process(PatientRawDataEntity rawData) {
        if (rawData == null || !StringUtils.hasText(rawData.getDataJson())) {
            return NormalizeRowProcessResult.failed(InfectionJobStage.NORMALIZE, "rawData为空或缺少dataJson");
        }

        try {
            patientService.resetDerivedDataForRawData(rawData.getId());
            DailyIllnessExtractionResult dailyIllnessResult = normalizeStructDataComposer.compose(rawData);
            rawData.setStructDataJson(dailyIllnessResult.structDataJson());
            rawData.setEventJson(dailyIllnessResult.dailySummaryJson());
            patientService.saveStructDataJson(
                    rawData.getId(),
                    dailyIllnessResult.structDataJson(),
                    dailyIllnessResult.dailySummaryJson()
            );
            return NormalizeRowProcessResult.success();
        } catch (Exception e) {
            log.error("病程增量摘要失败，rowId={}, reqno={}", rawData.getId(), rawData.getReqno(), e);
            return NormalizeRowProcessResult.failed(InfectionJobStage.NORMALIZE, RETRY_MESSAGE);
        }
    }
}
