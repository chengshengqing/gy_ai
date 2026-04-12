package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.domain.enums.InfectionTriggerPriority;
import com.zzhy.yg_ai.mapper.InfectionEventTaskMapper;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
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

    private static final int CASE_HIGH_DEBOUNCE_MINUTES = 5;
    private static final int CASE_NORMAL_DEBOUNCE_MINUTES = 10;

    private final StructDataFormatProperties structDataFormatProperties;
    private final InfectionEventPoolService infectionEventPoolService;

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
        String mergeKey = buildCaseMergeKey(reqno);
        InfectionEventTaskEntity existing = findByTaskTypeAndMergeKey(InfectionEventTaskType.CASE_RECOMPUTE, mergeKey);
        LocalDateTime now = DateTimeUtils.now();
        String triggerPriority = resolveTriggerPriority(priority);
        LocalDateTime debounceUntil = now.plusMinutes(resolveDebounceMinutes(triggerPriority));
        Long latestEventPoolVersion = infectionEventPoolService.getLatestActiveEventVersion(reqno.trim());
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
            task.setFirstTriggeredAt(now);
            task.setLastEventAt(now);
            task.setDebounceUntil(debounceUntil);
            task.setTriggerPriority(triggerPriority);
            task.setEventPoolVersionAtEnqueue(latestEventPoolVersion);
            task.initForCreate(structDataFormatProperties.getMaxAttempts());
            task.setAvailableAt(debounceUntil);
            save(task);
            return;
        }

        LambdaUpdateWrapper<InfectionEventTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(InfectionEventTaskEntity::getId, existing.getId())
                .set(InfectionEventTaskEntity::getPriority, Math.min(safePriority(existing.getPriority()), safePriority(priority)))
                .set(InfectionEventTaskEntity::getSourceBatchTime, sourceBatchTime)
                .set(InfectionEventTaskEntity::getLastEventAt, now)
                .set(InfectionEventTaskEntity::getDebounceUntil, debounceUntil)
                .set(InfectionEventTaskEntity::getTriggerPriority, upgradeTriggerPriority(existing.getTriggerPriority(), triggerPriority))
                .set(InfectionEventTaskEntity::getEventPoolVersionAtEnqueue, latestEventPoolVersion)
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        if (!InfectionEventTaskStatus.RUNNING.name().equals(existing.getStatus())) {
            updateWrapper.set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.PENDING.name())
                    .set(InfectionEventTaskEntity::getAvailableAt, debounceUntil)
                    .set(InfectionEventTaskEntity::getAttemptCount, 0)
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
        List<InfectionEventTaskEntity> claimed = this.baseMapper.claimPendingTasks(taskType.name(), now, batchSize);
        return claimed == null ? List.of() : claimed;
    }

    @Override
    public void markSuccess(List<Long> taskIds, String message) {
        updateFinishedTasks(taskIds, InfectionEventTaskStatus.SUCCESS, message, false);
    }

    @Override
    public boolean markSuccessOrRequeueIfEventVersionAdvanced(List<Long> taskIds,
                                                             Long processedEventPoolVersion,
                                                             String successMessage,
                                                             String requeueMessage) {
        if (taskIds == null || taskIds.isEmpty()) {
            return false;
        }
        LocalDateTime now = DateTimeUtils.now();
        long processedVersion = processedEventPoolVersion == null ? 0L : processedEventPoolVersion;

        LambdaUpdateWrapper<InfectionEventTaskEntity> successWrapper = new LambdaUpdateWrapper<>();
        successWrapper.in(InfectionEventTaskEntity::getId, taskIds)
                .and(wrapper -> wrapper
                        .isNull(InfectionEventTaskEntity::getEventPoolVersionAtEnqueue)
                        .or()
                        .le(InfectionEventTaskEntity::getEventPoolVersionAtEnqueue, processedVersion))
                .set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.SUCCESS.name())
                .set(InfectionEventTaskEntity::getLastFinishTime, now)
                .set(InfectionEventTaskEntity::getLastErrorMessage, successMessage)
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        this.baseMapper.update(null, successWrapper);

        LambdaUpdateWrapper<InfectionEventTaskEntity> requeueWrapper = new LambdaUpdateWrapper<>();
        requeueWrapper.in(InfectionEventTaskEntity::getId, taskIds)
                .gt(InfectionEventTaskEntity::getEventPoolVersionAtEnqueue, processedVersion)
                .set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.PENDING.name())
                .set(InfectionEventTaskEntity::getAttemptCount, 0)
                .set(InfectionEventTaskEntity::getLastFinishTime, now)
                .set(InfectionEventTaskEntity::getLastErrorMessage, requeueMessage)
                .set(InfectionEventTaskEntity::getUpdateTime, now)
                .setSql("available_at = COALESCE(debounce_until, GETDATE())");
        return this.baseMapper.update(null, requeueWrapper) > 0;
    }

    @Override
    public void markSkipped(List<Long> taskIds, String message) {
        updateFinishedTasks(taskIds, InfectionEventTaskStatus.SKIPPED, message, false);
    }

    @Override
    public void markFailed(List<Long> taskIds, String errorMessage) {
        updateFinishedTasks(taskIds, InfectionEventTaskStatus.FAILED, errorMessage, true);
    }

    @Override
    public void reschedule(List<Long> taskIds, LocalDateTime availableAt, String message) {
        if (taskIds == null || taskIds.isEmpty() || availableAt == null) {
            return;
        }
        LocalDateTime now = DateTimeUtils.now();
        LambdaUpdateWrapper<InfectionEventTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(InfectionEventTaskEntity::getId, taskIds)
                .set(InfectionEventTaskEntity::getStatus, InfectionEventTaskStatus.PENDING.name())
                .set(InfectionEventTaskEntity::getAvailableAt, availableAt)
                .set(InfectionEventTaskEntity::getDebounceUntil, availableAt)
                .set(InfectionEventTaskEntity::getLastErrorMessage, message)
                .set(InfectionEventTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
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

    private String buildCaseMergeKey(String reqno) {
        return "CASE_RECOMPUTE:" + reqno.trim();
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

    private String resolveTriggerPriority(int priority) {
        return priority <= 10 ? InfectionTriggerPriority.HIGH.code() : InfectionTriggerPriority.NORMAL.code();
    }

    private int resolveDebounceMinutes(String triggerPriority) {
        InfectionTriggerPriority tp = InfectionTriggerPriority.fromCodeOrDefault(triggerPriority, InfectionTriggerPriority.NORMAL);
        return tp == InfectionTriggerPriority.HIGH ? CASE_HIGH_DEBOUNCE_MINUTES : CASE_NORMAL_DEBOUNCE_MINUTES;
    }

    private String upgradeTriggerPriority(String existing, String incoming) {
        InfectionTriggerPriority existingTp = InfectionTriggerPriority.fromCodeOrDefault(existing, InfectionTriggerPriority.NORMAL);
        InfectionTriggerPriority incomingTp = InfectionTriggerPriority.fromCodeOrDefault(incoming, InfectionTriggerPriority.NORMAL);
        return InfectionTriggerPriority.upgrade(existingTp, incomingTp).code();
    }
}
