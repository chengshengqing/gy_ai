package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface InfectionEventTaskService extends IService<InfectionEventTaskEntity> {

    void upsertEventExtractTask(Long patientRawDataId,
                                String reqno,
                                LocalDate dataDate,
                                LocalDateTime rawDataLastTime,
                                LocalDateTime sourceBatchTime,
                                String changedTypes,
                                String triggerReasonCodes,
                                int priority);

    void upsertCaseRecomputeTask(String reqno,
                                 Long patientRawDataId,
                                 LocalDate dataDate,
                                 LocalDateTime rawDataLastTime,
                                 LocalDateTime sourceBatchTime,
                                 int bucketMinutes,
                                 int priority);

    List<InfectionEventTaskEntity> claimPendingTasks(InfectionEventTaskType taskType, int limit);

    void markSuccess(List<Long> taskIds, String message);

    void markSkipped(List<Long> taskIds, String message);

    void markFailed(List<Long> taskIds, String errorMessage);
}
