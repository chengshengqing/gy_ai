package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.PatientStructDataTaskEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface PatientStructDataTaskService extends IService<PatientStructDataTaskEntity> {

    void enqueue(String reqno, LocalDateTime latestRawDataTime, LocalDate replayFromDate);

    List<PatientStructDataTaskEntity> claimPendingTasks(int limit);

    void markSuccess(Long taskId, String message);

    void markFailed(Long taskId, String errorMessage);
}
