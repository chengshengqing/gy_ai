package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.pipeline.model.NormalizeResult;
import com.zzhy.yg_ai.service.PatientService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NormalizeHandler extends AbstractTaskHandler<List<PatientRawDataChangeTaskEntity>, NormalizeResult> {

    private final NormalizeRowProcessor normalizeRowProcessor;
    private final NormalizeResultApplier normalizeResultApplier;
    private final PatientService patientService;

    @Override
    protected NormalizeResult process(List<PatientRawDataChangeTaskEntity> taskGroup) {
        return processTaskGroup(taskGroup);
    }

    @Override
    protected void afterHandle(NormalizeResult result) {
        normalizeResultApplier.apply(result);
    }

    private NormalizeResult processTaskGroup(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = extractTaskIds(taskGroup);
        String reqno = resolveReqno(taskGroup);
        if (!StringUtils.hasText(reqno)) {
            return new NormalizeResult(taskIds, reqno, 0, 0, Math.max(1, taskIds.size()), "reqno为空", InfectionJobStage.NORMALIZE);
        }

        List<PatientRawDataEntity> rows = collectFreshRawData(taskGroup);
        if (rows.isEmpty()) {
            return new NormalizeResult(taskIds, reqno, 0, 0, 0, null, InfectionJobStage.NORMALIZE);
        }

        int successCount = 0;
        int failedCount = 0;
        String lastErrorMessage = null;
        InfectionJobStage failedStage = InfectionJobStage.NORMALIZE;
        for (PatientRawDataEntity rawData : rows) {
            NormalizeRowProcessResult rowResult = normalizeRowProcessor.process(rawData);
            if (!rowResult.isSuccess()) {
                failedCount++;
                lastErrorMessage = StringUtils.hasText(rowResult.errorMessage())
                        ? rowResult.errorMessage()
                        : "存在未完成的 rawData 行，需重试";
                failedStage = rowResult.failedStage() == null ? InfectionJobStage.NORMALIZE : rowResult.failedStage();
                continue;
            }
            successCount++;
        }

        return new NormalizeResult(taskIds, reqno, rows.size(), successCount, failedCount, lastErrorMessage, failedStage);
    }

    private List<Long> extractTaskIds(List<PatientRawDataChangeTaskEntity> taskGroup) {
        List<Long> taskIds = new ArrayList<>();
        if (taskGroup == null || taskGroup.isEmpty()) {
            return taskIds;
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity != null && taskEntity.getId() != null) {
                taskIds.add(taskEntity.getId());
            }
        }
        return taskIds;
    }

    private String resolveReqno(List<PatientRawDataChangeTaskEntity> taskGroup) {
        if (taskGroup == null || taskGroup.isEmpty()) {
            return null;
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity != null && StringUtils.hasText(taskEntity.getReqno())) {
                return taskEntity.getReqno().trim();
            }
        }
        return null;
    }

    private List<PatientRawDataEntity> collectFreshRawData(List<PatientRawDataChangeTaskEntity> taskGroup) {
        Map<String, PatientRawDataEntity> rows = new LinkedHashMap<>();
        if (taskGroup == null || taskGroup.isEmpty()) {
            return List.of();
        }
        for (PatientRawDataChangeTaskEntity taskEntity : taskGroup) {
            if (taskEntity == null || taskEntity.getPatientRawDataId() == null || taskEntity.getRawDataLastTime() == null) {
                continue;
            }
            PatientRawDataEntity rawData = patientService.getRawDataById(taskEntity.getPatientRawDataId());
            if (rawData == null || rawData.getLastTime() == null) {
                continue;
            }
            if (!rawData.getLastTime().equals(taskEntity.getRawDataLastTime())) {
                continue;
            }
            rows.putIfAbsent(rawData.getId() + "|" + taskEntity.getRawDataLastTime(), rawData);
        }
        return new ArrayList<>(rows.values());
    }
}
