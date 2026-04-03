package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import java.time.LocalDateTime;
import java.util.List;

public interface PatientRawDataCollectTaskService extends IService<PatientRawDataCollectTaskEntity> {

    int enqueueBatch(List<String> reqnos, LocalDateTime sourceLastTime);

    List<PatientRawDataCollectTaskEntity> claimPendingTasks(int limit);

    LocalDateTime getLatestSourceLastTime();

    int reclaimTimedOutRunningTasks();

    void markSuccess(Long taskId, String message);

    void markFailed(Long taskId, String errorMessage);

    void updateChangeTypes(Long taskId, String changeTypes);
}
