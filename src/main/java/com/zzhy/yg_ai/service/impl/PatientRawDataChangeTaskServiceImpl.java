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
    public void appendChange(Long patientRawDataId, String reqno, LocalDate dataDate, LocalDateTime rawDataLastTime) {
        if (patientRawDataId == null || !StringUtils.hasText(reqno) || rawDataLastTime == null) {
            return;
        }
        PatientRawDataChangeTaskEntity task = new PatientRawDataChangeTaskEntity();
        task.setPatientRawDataId(patientRawDataId);
        task.setReqno(reqno.trim());
        task.setDataDate(dataDate);
        task.setRawDataLastTime(rawDataLastTime);
        task.initForCreate(structDataFormatProperties.getMaxAttempts());
        this.save(task);
    }

    @Override
    public void appendChanges(List<PatientRawDataChangeTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
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
            validTasks.add(task);
        }
        if (!validTasks.isEmpty()) {
            this.baseMapper.insertBatchWithoutId(validTasks);
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
    public void markStructSuccess(List<Long> taskIds, String message, boolean eventPending) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .set(PatientRawDataChangeTaskEntity::getStatus,
                        eventPending ? PatientRawDataChangeTaskStatus.EVENT_PENDING.name()
                                : PatientRawDataChangeTaskStatus.SUCCESS.name())
                .set(PatientRawDataChangeTaskEntity::getAvailableAt, now)
                .set(PatientRawDataChangeTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataChangeTaskEntity::getLastErrorMessage, message)
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now);
        if (eventPending) {
            updateWrapper.set(PatientRawDataChangeTaskEntity::getAttemptCount, 0);
        }
        this.update(updateWrapper);
    }

    @Override
    public void markStructFailed(List<Long> taskIds, String errorMessage) {
        markFailed(taskIds, errorMessage, PatientRawDataChangeTaskStatus.STRUCT_FAILED.name());
    }

    @Override
    public List<PatientRawDataChangeTaskEntity> claimPendingEventTasks(int patientLimit) {
        return claimPendingTasks(patientLimit,
                List.of(
                        PatientRawDataChangeTaskStatus.EVENT_PENDING.name(),
                        PatientRawDataChangeTaskStatus.EVENT_FAILED.name()
                ),
                PatientRawDataChangeTaskStatus.EVENT_RUNNING.name());
    }

    @Override
    public void markEventSuccess(List<Long> taskIds, String message) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .set(PatientRawDataChangeTaskEntity::getStatus, PatientRawDataChangeTaskStatus.SUCCESS.name())
                .set(PatientRawDataChangeTaskEntity::getLastFinishTime, now)
                .set(PatientRawDataChangeTaskEntity::getLastErrorMessage, message)
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    @Override
    public void markEventFailed(List<Long> taskIds, String errorMessage) {
        markFailed(taskIds, errorMessage, PatientRawDataChangeTaskStatus.EVENT_FAILED.name());
    }

    private List<PatientRawDataChangeTaskEntity> claimPendingTasks(int patientLimit,
                                                                   List<String> statuses,
                                                                   String runningStatus) {
        int batchSize = patientLimit <= 0 ? structDataFormatProperties.getBatchSize() : patientLimit;
        LocalDateTime now = DateTimeUtils.now();
        List<String> selectedReqnos = this.baseMapper.selectPendingReqnos(statuses, now, batchSize);
        if (selectedReqnos == null || selectedReqnos.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> reqnos = new ArrayList<>(selectedReqnos.size());
        for (String reqno : selectedReqnos) {
            if (StringUtils.hasText(reqno)) {
                reqnos.add(reqno.trim());
            }
        }
        if (reqnos.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<PatientRawDataChangeTaskEntity> pendingQueryWrapper = new LambdaQueryWrapper<>();
        pendingQueryWrapper.in(PatientRawDataChangeTaskEntity::getReqno, reqnos)
                .in(PatientRawDataChangeTaskEntity::getStatus, statuses)
                .le(PatientRawDataChangeTaskEntity::getAvailableAt, now)
                .apply("attempt_count < max_attempts")
                .orderByAsc(PatientRawDataChangeTaskEntity::getDataDate)
                .orderByAsc(PatientRawDataChangeTaskEntity::getCreateTime)
                .orderByAsc(PatientRawDataChangeTaskEntity::getId);
        List<PatientRawDataChangeTaskEntity> pendingTasks = this.list(pendingQueryWrapper);
        if (pendingTasks == null || pendingTasks.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> taskIds = new ArrayList<>(pendingTasks.size());
        for (PatientRawDataChangeTaskEntity pendingTask : pendingTasks) {
            if (pendingTask != null && pendingTask.getId() != null) {
                taskIds.add(pendingTask.getId());
            }
        }
        if (taskIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaUpdateWrapper<PatientRawDataChangeTaskEntity> claimWrapper = new LambdaUpdateWrapper<>();
        claimWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .in(PatientRawDataChangeTaskEntity::getStatus, statuses)
                .le(PatientRawDataChangeTaskEntity::getAvailableAt, now)
                .apply("attempt_count < max_attempts")
                .set(PatientRawDataChangeTaskEntity::getStatus, runningStatus)
                .set(PatientRawDataChangeTaskEntity::getLastStartTime, now)
                .set(PatientRawDataChangeTaskEntity::getUpdateTime, now)
                .setSql("attempt_count = attempt_count + 1");
        if (!this.update(claimWrapper)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<PatientRawDataChangeTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(PatientRawDataChangeTaskEntity::getId, taskIds)
                .eq(PatientRawDataChangeTaskEntity::getStatus, runningStatus)
                .orderByAsc(PatientRawDataChangeTaskEntity::getDataDate)
                .orderByAsc(PatientRawDataChangeTaskEntity::getCreateTime)
                .orderByAsc(PatientRawDataChangeTaskEntity::getId);
        return this.list(queryWrapper);
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
}
