package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface PatientRawDataChangeTaskService extends IService<PatientRawDataChangeTaskEntity> {

    void appendChange(Long patientRawDataId, String reqno, LocalDate dataDate, LocalDateTime rawDataLastTime);

    void appendChanges(List<PatientRawDataChangeTaskEntity> tasks);

    List<PatientRawDataChangeTaskEntity> claimPendingStructTasks(int patientLimit);

    void markStructSuccess(List<Long> taskIds, String message, boolean eventPending);

    void markStructFailed(List<Long> taskIds, String errorMessage);

    List<PatientRawDataChangeTaskEntity> claimPendingEventTasks(int patientLimit);

    void markEventSuccess(List<Long> taskIds, String message);

    void markEventFailed(List<Long> taskIds, String errorMessage);
}
