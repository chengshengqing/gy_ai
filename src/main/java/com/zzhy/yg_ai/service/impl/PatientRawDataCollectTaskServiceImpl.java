package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.enums.PatientRawDataTaskStatus;
import com.zzhy.yg_ai.mapper.PatientRawDataCollectTaskMapper;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PatientRawDataCollectTaskServiceImpl
        extends ServiceImpl<PatientRawDataCollectTaskMapper, PatientRawDataCollectTaskEntity>
        implements PatientRawDataCollectTaskService {

    private final InfectionMonitorProperties infectionMonitorProperties;

    @Override
    public int enqueueBatch(List<String> reqnos, LocalDateTime sourceLastTime) {
        int enqueued = 0;
        for (String reqno : reqnos) {
            if (!StringUtils.hasText(reqno)) {
                continue;
            }
            PatientRawDataCollectTaskEntity existing = this.lambdaQuery()
                    .eq(PatientRawDataCollectTaskEntity::getReqno, reqno)
                    .one();
            if (existing == null) {
                PatientRawDataCollectTaskEntity task = new PatientRawDataCollectTaskEntity();
                task.setReqno(reqno.trim());
                task.setSourceLastTime(sourceLastTime);
                task.setChangeTypes(null);
                task.initForCreate(infectionMonitorProperties.getMaxAttempts());
                this.save(task);
                enqueued++;
                continue;
            }

            boolean sourceChanged = sourceLastTime != null && !sourceLastTime.equals(existing.getSourceLastTime());
            boolean canRequeue = !PatientRawDataTaskStatus.RUNNING.name().equals(existing.getStatus());
            if (!sourceChanged && PatientRawDataTaskStatus.SUCCESS.name().equals(existing.getStatus())) {
                continue;
            }

            LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
            LocalDateTime now = DateTimeUtils.now();
            updateWrapper.eq(PatientRawDataCollectTaskEntity::getId, existing.getId())
                    .set(PatientRawDataCollectTaskEntity::getSourceLastTime, sourceLastTime)
                    .set(PatientRawDataCollectTaskEntity::getChangeTypes, null)
                    .set(PatientRawDataCollectTaskEntity::getUpdateTime, now)
                    .set(PatientRawDataCollectTaskEntity::getAvailableAt, now)
                    .set(PatientRawDataCollectTaskEntity::getLastErrorMessage, null);
            if (canRequeue) {
                updateWrapper.set(PatientRawDataCollectTaskEntity::getStatus, PatientRawDataTaskStatus.PENDING.name());
            }
            if (sourceChanged) {
                updateWrapper.set(PatientRawDataCollectTaskEntity::getAttemptCount, 0);
            }
            this.update(updateWrapper);
            if (canRequeue) {
                enqueued++;
            }
        }
        return enqueued;
    }

    @Override
    public List<PatientRawDataCollectTaskEntity> claimPendingTasks(int limit) {
        int batchSize = limit <= 0 ? infectionMonitorProperties.getBatchSize() : limit;
        LocalDateTime now = DateTimeUtils.now();
        LambdaQueryWrapper<PatientRawDataCollectTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(PatientRawDataCollectTaskEntity::getStatus,
                        PatientRawDataTaskStatus.PENDING.name(),
                        PatientRawDataTaskStatus.FAILED.name())
                .le(PatientRawDataCollectTaskEntity::getAvailableAt, now)
                .apply("attempt_count < max_attempts")
                .orderByAsc(PatientRawDataCollectTaskEntity::getAvailableAt)
                .orderByAsc(PatientRawDataCollectTaskEntity::getCreateTime)
                .last("OFFSET 0 ROWS FETCH NEXT " + batchSize + " ROWS ONLY");

        List<PatientRawDataCollectTaskEntity> candidates = this.list(queryWrapper);
        List<PatientRawDataCollectTaskEntity> claimed = new ArrayList<>();
        for (PatientRawDataCollectTaskEntity candidate : candidates) {
            LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> claimWrapper = new LambdaUpdateWrapper<>();
            claimWrapper.eq(PatientRawDataCollectTaskEntity::getId, candidate.getId())
                    .in(PatientRawDataCollectTaskEntity::getStatus,
                        PatientRawDataTaskStatus.PENDING.name(),
                        PatientRawDataTaskStatus.FAILED.name())
                    .set(PatientRawDataCollectTaskEntity::getStatus, PatientRawDataTaskStatus.RUNNING.name())
                    .set(PatientRawDataCollectTaskEntity::getLastStartTime, now)
                    .set(PatientRawDataCollectTaskEntity::getUpdateTime, now)
                    .setSql("attempt_count = attempt_count + 1");
            if (this.update(claimWrapper)) {
                candidate.setStatus(PatientRawDataTaskStatus.RUNNING.name());
                candidate.setAttemptCount((candidate.getAttemptCount() == null ? 0 : candidate.getAttemptCount()) + 1);
                candidate.setLastStartTime(now);
                candidate.setUpdateTime(now);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    @Override
    public void markSuccess(Long taskId, String message) {
        if (taskId == null) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientRawDataCollectTaskEntity::getId, taskId)
                .set(PatientRawDataCollectTaskEntity::getStatus, PatientRawDataTaskStatus.SUCCESS.name())
                .set(PatientRawDataCollectTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataCollectTaskEntity::getLastErrorMessage, message)
                .set(PatientRawDataCollectTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    @Override
    public void markFailed(Long taskId, String errorMessage) {
        if (taskId == null) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientRawDataCollectTaskEntity::getId, taskId)
                .set(PatientRawDataCollectTaskEntity::getStatus, PatientRawDataTaskStatus.FAILED.name())
                .set(PatientRawDataCollectTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataCollectTaskEntity::getAvailableAt,
                     now.plusSeconds(Math.max(1, infectionMonitorProperties.getRetryDelaySeconds())))
                .set(PatientRawDataCollectTaskEntity::getLastErrorMessage, errorMessage)
                .set(PatientRawDataCollectTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    @Override
    public void updateChangeTypes(Long taskId, String changeTypes) {
        if (taskId == null) {
            return;
        }
        LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientRawDataCollectTaskEntity::getId, taskId)
                .set(PatientRawDataCollectTaskEntity::getChangeTypes, changeTypes)
                .set(PatientRawDataCollectTaskEntity::getUpdateTime, DateTimeUtils.now());
        this.update(updateWrapper);
    }
}
