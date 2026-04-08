package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

public interface PatientRawDataChangeTaskService extends IService<PatientRawDataChangeTaskEntity> {

    void appendChange(Long patientRawDataId,
                      String reqno,
                      LocalDate dataDate,
                      LocalDateTime rawDataLastTime,
                      LocalDateTime sourceBatchTime);

    void appendChanges(List<PatientRawDataChangeTaskEntity> tasks);

    List<PatientRawDataChangeTaskEntity> claimPendingStructTasks(int patientLimit);

    void markStructSuccess(List<Long> taskIds, String message);

    void markStructSkipped(List<Long> taskIds, String message);

    void markStructFailed(List<Long> taskIds, String errorMessage);
}
