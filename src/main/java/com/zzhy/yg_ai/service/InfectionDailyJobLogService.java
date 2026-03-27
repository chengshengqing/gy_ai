package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.InfectionDailyJobLogEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import java.time.LocalDateTime;

public interface InfectionDailyJobLogService extends IService<InfectionDailyJobLogEntity> {

    void log(InfectionJobStage stage, InfectionJobStatus status, String reqno, String message);

    LocalDateTime getLatestSuccessTime(InfectionJobStage stage);
}
