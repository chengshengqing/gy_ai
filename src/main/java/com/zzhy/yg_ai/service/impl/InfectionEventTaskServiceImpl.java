package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.mapper.InfectionEventTaskMapper;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class InfectionEventTaskServiceImpl
        extends ServiceImpl<InfectionEventTaskMapper, InfectionEventTaskEntity>
        implements InfectionEventTaskService {

    private static final int DEFAULT_CASE_BUCKET_MINUTES = 30;

    private final StructDataFormatProperties structDataFormatProperties;

    @Override
    public void upsertEventExtractTask(Long patientRawDataId,
                                       String reqno,
                                       LocalDate dataDate,
                                       LocalDateTime rawDataLastTime,
                                       LocalDateTime sourceBatchTime,
                                       String changedTypes,
                                       String triggerReasonCodes,
                                       int priority) {
        if (patientRawDataId == null || !StringUtils.hasText(reqno) || rawDataLastTime == null || sourceBatchTime == null) {
            return;
        }
        String mergeKey = buildEventExtractMergeKey(patientRawDataId, rawDataLastTime);
        InfectionEventTaskEntity existing = findByTaskTypeAndMergeKey(InfectionEventTaskType.EVENT_EXTRACT, mergeKey);
        if (existing == null) {
            InfectionEventTaskEntity task = new InfectionEventTaskEntity();
            task.setTaskType(InfectionEventTaskType.EVENT_EXTRACT.name());
            task.setReqno(reqno.trim());
            task.setPatientRawDataId(patientRawDataId);
            task.setDataDate(dataDate);
            task.setRawDataLastTime(rawDataLastTime);
            task.setSourceBatchTime(sourceBatchTime);
            task.setChangedTypes(changedTypes);
            task.setTriggerReasonCodes(triggerReasonCodes);
            task.setPriority(priority);
            task.setMergeKey(mergeKey);
            task.initForCreate(structDataFormatProperties.getMaxAttempts());
            save(task);
            return;
        }

        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<InfectionEventTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(InfectionEventTaskEntity::getId, existing.getId())
                .set(InfectionEventTaskEntity::getChangedTypes, mergeCsv(existing.getChangedTypes(), changedTypes))
                .set(InfectionEventTaskEntity::getTriggerReasonCodes, mergeCsv(existing.getTriggerReasonCodes(), triggerReasonCodes))
                .set(InfectionEventTaskEntity::getPriority, Math.min(safePriority(existing.getPriority()), safePriority(priority)))
                .set(InfectionEventTaskEntity::getSourceBatchTime, sourceBatchTime)
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        if (!InfectionEventTaskStatus.RUNNING.name().equals(existing.getStatus())
                && !InfectionEventTaskStatus.SUCCESS.name().equals(existing.getStatus())) {
            updateWrapper.set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.PENDING.name())
                    .set(InfectionEventTaskEntity::getAvailableAt, now)
                    .set(InfectionEventTaskEntity::getLastErrorMessage, null);
        }
        this.update(updateWrapper);
    }

    @Override
    public void upsertCaseRecomputeTask(String reqno,
                                        Long patientRawDataId,
                                        LocalDate dataDate,
                                        LocalDateTime rawDataLastTime,
                                        LocalDateTime sourceBatchTime,
                                        int bucketMinutes,
                                        int priority) {
        if (!StringUtils.hasText(reqno) || sourceBatchTime == null) {
            return;
        }
        int effectiveBucketMinutes = bucketMinutes <= 0 ? DEFAULT_CASE_BUCKET_MINUTES : bucketMinutes;
        String mergeKey = buildCaseMergeKey(reqno, sourceBatchTime, effectiveBucketMinutes);
        InfectionEventTaskEntity existing = findByTaskTypeAndMergeKey(InfectionEventTaskType.CASE_RECOMPUTE, mergeKey);
        if (existing == null) {
            InfectionEventTaskEntity task = new InfectionEventTaskEntity();
            task.setTaskType(InfectionEventTaskType.CASE_RECOMPUTE.name());
            task.setReqno(reqno.trim());
            task.setPatientRawDataId(patientRawDataId);
            task.setDataDate(dataDate);
            task.setRawDataLastTime(rawDataLastTime);
            task.setSourceBatchTime(sourceBatchTime);
            task.setPriority(priority);
            task.setMergeKey(mergeKey);
            task.initForCreate(structDataFormatProperties.getMaxAttempts());
            save(task);
            return;
        }

        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<InfectionEventTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(InfectionEventTaskEntity::getId, existing.getId())
                .set(InfectionEventTaskEntity::getPriority, Math.min(safePriority(existing.getPriority()), safePriority(priority)))
                .set(InfectionEventTaskEntity::getSourceBatchTime, sourceBatchTime)
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        if (!InfectionEventTaskStatus.RUNNING.name().equals(existing.getStatus())
                && !InfectionEventTaskStatus.SUCCESS.name().equals(existing.getStatus())) {
            updateWrapper.set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.PENDING.name())
                    .set(InfectionEventTaskEntity::getAvailableAt, now)
                    .set(InfectionEventTaskEntity::getLastErrorMessage, null);
        }
        this.update(updateWrapper);
    }

    @Override
    public List<InfectionEventTaskEntity> claimPendingTasks(InfectionEventTaskType taskType, int limit) {
        if (taskType == null) {
            return List.of();
        }
        int batchSize = limit <= 0 ? structDataFormatProperties.getBatchSize() : limit;
        LocalDateTime now = DateTimeUtils.now();
        reclaimTimedOutRunningTasks(taskType, now);
        LambdaQueryWrapper<InfectionEventTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(InfectionEventTaskEntity::getTaskType, taskType.name())
                .in(InfectionEventTaskEntity::getStatus,
                        InfectionEventTaskStatus.PENDING.name(),
                        InfectionEventTaskStatus.FAILED.name())
                .le(InfectionEventTaskEntity::getAvailableAt, now)
                .apply("attempt_count < max_attempts")
                .orderByAsc(InfectionEventTaskEntity::getPriority)
                .orderByAsc(InfectionEventTaskEntity::getAvailableAt)
                .orderByAsc(InfectionEventTaskEntity::getCreateTime)
                .last("OFFSET 0 ROWS FETCH NEXT " + batchSize + " ROWS ONLY");

        List<InfectionEventTaskEntity> candidates = this.list(queryWrapper);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<InfectionEventTaskEntity> claimed = new ArrayList<>();
        for (InfectionEventTaskEntity candidate : candidates) {
            LambdaUpdateWrapper<InfectionEventTaskEntity> claimWrapper = new LambdaUpdateWrapper<>();
            claimWrapper.eq(InfectionEventTaskEntity::getId, candidate.getId())
                    .eq(InfectionEventTaskEntity::getTaskType, taskType.name())
                    .in(InfectionEventTaskEntity::getStatus,
                            InfectionEventTaskStatus.PENDING.name(),
                            InfectionEventTaskStatus.FAILED.name())
                    .set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.RUNNING.name())
                    .set(InfectionEventTaskEntity::getLastStartTime, now)
                    .set(InfectionEventTaskEntity::getUpdateTime, now)
                    .setSql("attempt_count = attempt_count + 1");
            if (this.update(claimWrapper)) {
                candidate.setStatus(InfectionEventTaskStatus.RUNNING.name());
                candidate.setAttemptCount((candidate.getAttemptCount() == null ? 0 : candidate.getAttemptCount()) + 1);
                candidate.setLastStartTime(now);
                candidate.setUpdateTime(now);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    @Override
    public void markSuccess(List<Long> taskIds, String message) {
        updateFinishedTasks(taskIds, InfectionEventTaskStatus.SUCCESS, message, false);
    }

    @Override
    public void markSkipped(List<Long> taskIds, String message) {
        updateFinishedTasks(taskIds, InfectionEventTaskStatus.SKIPPED, message, false);
    }

    @Override
    public void markFailed(List<Long> taskIds, String errorMessage) {
        updateFinishedTasks(taskIds, InfectionEventTaskStatus.FAILED, errorMessage, true);
    }

    private InfectionEventTaskEntity findByTaskTypeAndMergeKey(InfectionEventTaskType taskType, String mergeKey) {
        if (taskType == null || !StringUtils.hasText(mergeKey)) {
            return null;
        }
        return this.lambdaQuery()
                .eq(InfectionEventTaskEntity::getTaskType, taskType.name())
                .eq(InfectionEventTaskEntity::getMergeKey, mergeKey)
                .one();
    }

    private void reclaimTimedOutRunningTasks(InfectionEventTaskType taskType, LocalDateTime now) {
        if (taskType == null || now == null) {
            return;
        }
        LocalDateTime timeoutAt = now.minusSeconds(Math.max(1, structDataFormatProperties.getRunningTimeoutSeconds()));
        LambdaUpdateWrapper<InfectionEventTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(InfectionEventTaskEntity::getTaskType, taskType.name())
                .eq(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.RUNNING.name())
                .isNotNull(InfectionEventTaskEntity::getLastStartTime)
                .le(InfectionEventTaskEntity::getLastStartTime, timeoutAt)
                .apply("attempt_count < max_attempts")
                .set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.FAILED.name())
                .set(InfectionEventTaskEntity::getAvailableAt, now)
                .set(InfectionEventTaskEntity::getLastFinishTime, now)
                .set(InfectionEventTaskEntity::getLastErrorMessage, "任务运行超时，已回收待重试")
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    private void updateFinishedTasks(List<Long> taskIds,
                                     InfectionEventTaskStatus status,
                                     String message,
                                     boolean delayedRetry) {
        if (taskIds == null || taskIds.isEmpty() || status == null) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<InfectionEventTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(InfectionEventTaskEntity::getId, taskIds)
                .set(InfectionEventTaskEntity::getStatus, status.name())
                .set(InfectionEventTaskEntity::getLastFinishTime, now)
                .set(InfectionEventTaskEntity::getLastErrorMessage, message)
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        if (delayedRetry) {
            updateWrapper.set(InfectionEventTaskEntity::getAvailableAt,
                    now.plusSeconds(Math.max(1, structDataFormatProperties.getRetryDelaySeconds())));
        }
        this.update(updateWrapper);
    }

    private String buildEventExtractMergeKey(Long patientRawDataId, LocalDateTime rawDataLastTime) {
        return "raw:" + patientRawDataId + ":" + rawDataLastTime.truncatedTo(ChronoUnit.SECONDS);
    }

    private String buildCaseMergeKey(String reqno, LocalDateTime sourceBatchTime, int bucketMinutes) {
        LocalDateTime bucketStart = sourceBatchTime
                .truncatedTo(ChronoUnit.MINUTES)
                .minusMinutes(sourceBatchTime.getMinute() % Math.max(1, bucketMinutes));
        return "case:" + reqno.trim() + ":" + bucketStart;
    }

    private int safePriority(Integer priority) {
        return priority == null ? 100 : priority;
    }

    private String mergeCsv(String existingCsv, String incomingCsv) {
        if (!StringUtils.hasText(existingCsv)) {
            return StringUtils.hasText(incomingCsv) ? incomingCsv.trim() : null;
        }
        if (!StringUtils.hasText(incomingCsv)) {
            return existingCsv;
        }
        List<String> ordered = new ArrayList<>();
        for (String token : (existingCsv + "," + incomingCsv).split(",")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String trimmed = token.trim();
            if (!ordered.contains(trimmed)) {
                ordered.add(trimmed);
            }
        }
        return ordered.isEmpty() ? null : String.join(",", ordered);
    }
}
