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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
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
                task.setPreviousSourceLastTime(null);
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
                    .set(PatientRawDataCollectTaskEntity::getPreviousSourceLastTime, existing.getSourceLastTime())
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
    public LocalDateTime getLatestSourceLastTime() {
        PatientRawDataCollectTaskEntity latest = this.lambdaQuery()
                .isNotNull(PatientRawDataCollectTaskEntity::getSourceLastTime)
                .orderByDesc(PatientRawDataCollectTaskEntity::getSourceLastTime)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY")
                .one();
        return latest == null ? null : latest.getSourceLastTime();
    }

    @Override
    public List<PatientRawDataCollectTaskEntity> claimPendingTasks(int limit) {
        int batchSize = limit <= 0 ? infectionMonitorProperties.getBatchSize() : limit;
        LocalDateTime now = DateTimeUtils.now();
        int reclaimedCount = reclaimTimedOutRunningTasks(now);
        if (reclaimedCount > 0) {
            log.warn("回收超时采集任务，count={}", reclaimedCount);
        }
        List<PatientRawDataCollectTaskEntity> claimed = this.baseMapper.claimPendingTasks(now, batchSize);
        return claimed == null ? new ArrayList<>() : claimed;
    }

    private int reclaimTimedOutRunningTasks(LocalDateTime now) {
        if (now == null) {
            return 0;
        }
        LocalDateTime timeoutAt = now.minusSeconds(Math.max(1, infectionMonitorProperties.getRunningTimeoutSeconds()));
        LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientRawDataCollectTaskEntity::getStatus, PatientRawDataTaskStatus.RUNNING.name())
                .isNotNull(PatientRawDataCollectTaskEntity::getLastStartTime)
                .le(PatientRawDataCollectTaskEntity::getLastStartTime, timeoutAt)
                .apply("attempt_count < max_attempts")
                .set(PatientRawDataCollectTaskEntity::getStatus, PatientRawDataTaskStatus.FAILED.name())
                .set(PatientRawDataCollectTaskEntity::getAvailableAt, now)
                .set(PatientRawDataCollectTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataCollectTaskEntity::getLastErrorMessage, "任务运行超时，已回收待重试")
                .set(PatientRawDataCollectTaskEntity::getUpdateTime, now);
        return this.baseMapper.update(null, updateWrapper);
    }

    @Override
    public void markSuccess(Long taskId, String message, String changeTypes) {
        updateTaskResult(taskId,
                PatientRawDataTaskStatus.SUCCESS,
                message,
                changeTypes,
                false);
    }

    @Override
    public void markFailed(Long taskId, String errorMessage, String changeTypes) {
        updateTaskResult(taskId,
                PatientRawDataTaskStatus.FAILED,
                errorMessage,
                changeTypes,
                true);
    }

    private void updateTaskResult(Long taskId,
                                  PatientRawDataTaskStatus status,
                                  String message,
                                  String changeTypes,
                                  boolean delayedRetry) {
        if (taskId == null || status == null) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataCollectTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientRawDataCollectTaskEntity::getId, taskId)
                .set(PatientRawDataCollectTaskEntity::getStatus, status.name())
                .set(PatientRawDataCollectTaskEntity::getChangeTypes, changeTypes)
                .set(PatientRawDataCollectTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataCollectTaskEntity::getLastErrorMessage, message)
                .set(PatientRawDataCollectTaskEntity::getUpdateTime, now);
        if (delayedRetry) {
            updateWrapper.set(PatientRawDataCollectTaskEntity::getAvailableAt,
                    now.plusSeconds(Math.max(1, infectionMonitorProperties.getRetryDelaySeconds())));
        }
        this.update(updateWrapper);
    }
}
