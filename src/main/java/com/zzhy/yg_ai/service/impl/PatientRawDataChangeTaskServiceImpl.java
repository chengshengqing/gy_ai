package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.enums.PatientRawDataChangeTaskStatus;
import com.zzhy.yg_ai.mapper.PatientRawDataChangeTaskMapper;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PatientRawDataChangeTaskServiceImpl
        extends ServiceImpl<PatientRawDataChangeTaskMapper, PatientRawDataChangeTaskEntity>
        implements PatientRawDataChangeTaskService {

    private final StructDataFormatProperties structDataFormatProperties;

    @Override
    public void appendChange(Long patientRawDataId,
                             String reqno,
                             LocalDate dataDate,
                             LocalDateTime rawDataLastTime,
                             LocalDateTime sourceBatchTime) {
        if (patientRawDataId == null || !StringUtils.hasText(reqno) || rawDataLastTime == null) {
            return;
        }
        if (taskExists(patientRawDataId, rawDataLastTime)) {
            return;
        }
        PatientRawDataChangeTaskEntity task = new PatientRawDataChangeTaskEntity();
        task.setPatientRawDataId(patientRawDataId);
        task.setReqno(reqno.trim());
        task.setDataDate(dataDate);
        task.setRawDataLastTime(rawDataLastTime);
        task.setSourceBatchTime(sourceBatchTime);
        task.initForCreate(structDataFormatProperties.getMaxAttempts());
        saveIfAbsent(task);
    }

    @Override
    public void appendChanges(List<PatientRawDataChangeTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        LinkedHashMap<String, PatientRawDataChangeTaskEntity> deduplicated = new LinkedHashMap<>();
        List<PatientRawDataChangeTaskEntity> validTasks = new ArrayList<>();
        for (PatientRawDataChangeTaskEntity task : tasks) {
            if (task == null
                    || task.getPatientRawDataId() == null
                    || !StringUtils.hasText(task.getReqno())
                    || task.getRawDataLastTime() == null) {
                continue;
            }
            if (task.getCreateTime() == null
                    || task.getUpdateTime() == null
                    || task.getAvailableAt() == null
                    || task.getStatus() == null
                    || task.getAttemptCount() == null
                    || task.getMaxAttempts() == null) {
                task.initForCreate(structDataFormatProperties.getMaxAttempts());
            }
            deduplicated.putIfAbsent(task.getPatientRawDataId() + "|" + task.getRawDataLastTime(), task);
        }
        validTasks.addAll(deduplicated.values());
        if (!validTasks.isEmpty()) {
            try {
                this.baseMapper.insertBatchWithoutId(validTasks);
            } catch (Exception e) {
                for (PatientRawDataChangeTaskEntity task : validTasks) {
                    saveIfAbsent(task);
                }
            }
        }
    }

    @Override
    public List<PatientRawDataChangeTaskEntity> claimPendingStructTasks(int patientLimit) {
        return claimPendingTasks(patientLimit,
                List.of(
                        PatientRawDataChangeTaskStatus.STRUCT_PENDING.name(),
                        PatientRawDataChangeTaskStatus.STRUCT_FAILED.name()
                ),
                PatientRawDataChangeTaskStatus.STRUCT_RUNNING.name());
    }

    @Override
    public void markStructSuccess(List<Long> taskIds, String message) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .set(PatientRawDataChangeTaskEntity::getStatus, PatientRawDataChangeTaskStatus.SUCCESS.name())
                .set(PatientRawDataChangeTaskEntity::getAvailableAt, now)
                .set(PatientRawDataChangeTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataChangeTaskEntity::getLastErrorMessage, message)
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    @Override
    public void markStructSkipped(List<Long> taskIds, String message) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .set(PatientRawDataChangeTaskEntity::getStatus, PatientRawDataChangeTaskStatus.SKIPPED.name())
                .set(PatientRawDataChangeTaskEntity::getAvailableAt, now)
                .set(PatientRawDataChangeTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataChangeTaskEntity::getLastErrorMessage, message)
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    @Override
    public void markStructFailed(List<Long> taskIds, String errorMessage) {
        markFailed(taskIds, errorMessage, PatientRawDataChangeTaskStatus.STRUCT_FAILED.name());
    }

    private List<PatientRawDataChangeTaskEntity> claimPendingTasks(int patientLimit,
                                                                   List<String> statuses,
                                                                   String runningStatus) {
        int batchSize = patientLimit <= 0 ? structDataFormatProperties.getBatchSize() : patientLimit;
        LocalDateTime now = DateTimeUtils.now();
        reclaimTimedOutRunningTasks(now, statuses, runningStatus);
        List<PatientRawDataChangeTaskEntity> claimedTasks =
                this.baseMapper.claimPendingTasks(statuses, runningStatus, now, batchSize);
        if (claimedTasks == null || claimedTasks.isEmpty()) {
            return Collections.emptyList();
        }
        return claimedTasks;
    }

    private void reclaimTimedOutRunningTasks(LocalDateTime now, List<String> pendingStatuses, String runningStatus) {
        if (now == null || pendingStatuses == null || pendingStatuses.isEmpty() || !StringUtils.hasText(runningStatus)) {
            return;
        }
        String failedStatus = resolveFailedStatus(pendingStatuses);
        if (!StringUtils.hasText(failedStatus)) {
            return;
        }
        LocalDateTime timeoutAt = now.minusSeconds(Math.max(1, structDataFormatProperties.getRunningTimeoutSeconds()));
        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientRawDataChangeTaskEntity::getStatus, runningStatus)
                .isNotNull(PatientRawDataChangeTaskEntity::getLastStartTime)
                .le(PatientRawDataChangeTaskEntity::getLastStartTime, timeoutAt)
                .apply("attempt_count < max_attempts")
                .set(PatientRawDataChangeTaskEntity::getStatus, failedStatus)
                .set(PatientRawDataChangeTaskEntity::getAvailableAt, now)
                .set(PatientRawDataChangeTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataChangeTaskEntity::getLastErrorMessage, "任务运行超时，已回收待重试")
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    private String resolveFailedStatus(List<String> pendingStatuses) {
        for (String status : pendingStatuses) {
            if (PatientRawDataChangeTaskStatus.STRUCT_FAILED.name().equals(status)) {
                return status;
            }
        }
        return null;
    }

    private void markFailed(List<Long> taskIds, String errorMessage, String failedStatus) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .set(PatientRawDataChangeTaskEntity::getStatus, failedStatus)
                .set(PatientRawDataChangeTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataChangeTaskEntity::getAvailableAt,
                        now.plusSeconds(Math.max(1, structDataFormatProperties.getRetryDelaySeconds())))
                .set(PatientRawDataChangeTaskEntity::getLastErrorMessage, errorMessage)
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    private boolean taskExists(Long rawDataId, LocalDateTime rawDataLastTime) {
        if (rawDataId == null || rawDataLastTime == null) {
            return false;
        }
        return this.count(new LambdaQueryWrapper<PatientRawDataChangeTaskEntity>()
                .eq(PatientRawDataChangeTaskEntity::getPatientRawDataId, rawDataId)
                .eq(PatientRawDataChangeTaskEntity::getRawDataLastTime, rawDataLastTime)) > 0;
    }

    private void saveIfAbsent(PatientRawDataChangeTaskEntity task) {
        if (task == null || task.getPatientRawDataId() == null || task.getRawDataLastTime() == null) {
            return;
        }
        if (taskExists(task.getPatientRawDataId(), task.getRawDataLastTime())) {
            return;
        }
        try {
            this.save(task);
        } catch (Exception e) {
            if (!taskExists(task.getPatientRawDataId(), task.getRawDataLastTime())) {
                throw e;
            }
        }
    }
}
