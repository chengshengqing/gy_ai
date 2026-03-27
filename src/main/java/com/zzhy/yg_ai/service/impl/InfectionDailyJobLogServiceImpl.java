package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.InfectionDailyJobLogEntity;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.mapper.InfectionDailyJobLogMapper;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class InfectionDailyJobLogServiceImpl
        extends ServiceImpl<InfectionDailyJobLogMapper, InfectionDailyJobLogEntity>
        implements InfectionDailyJobLogService {

    @Override
    public void log(InfectionJobStage stage, InfectionJobStatus status, String reqno, String message) {
        InfectionDailyJobLogEntity entity = new InfectionDailyJobLogEntity();
        entity.setStage(stage.name());
        entity.setStatus(status.name());
        entity.setReqno(reqno);
        entity.setMessage(message);
        entity.init();
        this.save(entity);
    }

    @Override
    public LocalDateTime getLatestSuccessTime(InfectionJobStage stage) {
        InfectionDailyJobLogEntity latest = this.lambdaQuery()
                .eq(InfectionDailyJobLogEntity::getStage, stage.name())
                .eq(InfectionDailyJobLogEntity::getStatus, InfectionJobStatus.SUCCESS.name())
                .orderByDesc(InfectionDailyJobLogEntity::getCreateTime)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY")
                .one();
        return latest == null ? null : latest.getCreateTime();
    }
}
