package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.PatientStructDataTaskEntity;
import com.zzhy.yg_ai.domain.enums.StructDataTaskStatus;
import com.zzhy.yg_ai.mapper.PatientStructDataTaskMapper;
import com.zzhy.yg_ai.service.PatientStructDataTaskService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PatientStructDataTaskServiceImpl
        extends ServiceImpl<PatientStructDataTaskMapper, PatientStructDataTaskEntity>
        implements PatientStructDataTaskService {

    private final StructDataFormatProperties structDataFormatProperties;

    @Override
    public void enqueue(String reqno, LocalDateTime latestRawDataTime, LocalDate replayFromDate) {
        if (!StringUtils.hasText(reqno)) {
            return;
        }
        PatientStructDataTaskEntity existing = this.lambdaQuery()
                .eq(PatientStructDataTaskEntity::getReqno, reqno)
                .one();
        if (existing == null) {
            PatientStructDataTaskEntity task = new PatientStructDataTaskEntity();
            task.setReqno(reqno);
            task.setLatestRawDataTime(latestRawDataTime);
            task.setReplayFromDate(replayFromDate);
            task.initForCreate(structDataFormatProperties.getMaxAttempts());
            this.save(task);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate mergedReplayFromDate = minDate(existing.getReplayFromDate(), replayFromDate);
        LambdaUpdateWrapper<PatientStructDataTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientStructDataTaskEntity::getId, existing.getId())
                .set(PatientStructDataTaskEntity::getLatestRawDataTime, latestRawDataTime)
                .set(PatientStructDataTaskEntity::getReplayFromDate, mergedReplayFromDate)
                .set(PatientStructDataTaskEntity::getUpdateTime, now)
                .set(PatientStructDataTaskEntity::getAvailableAt, now)
                .set(PatientStructDataTaskEntity::getLastErrorMessage, null);
        if (!StructDataTaskStatus.RUNNING.name().equals(existing.getStatus())) {
            updateWrapper.set(PatientStructDataTaskEntity::getStatus, StructDataTaskStatus.PENDING.name());
        }
        this.update(updateWrapper);
    }

    @Override
    public List<PatientStructDataTaskEntity> claimPendingTasks(int limit) {
        int batchSize = limit <= 0 ? structDataFormatProperties.getBatchSize() : limit;
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<PatientStructDataTaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(PatientStructDataTaskEntity::getStatus,
                        StructDataTaskStatus.PENDING.name(),
                        StructDataTaskStatus.FAILED.name())
                .le(PatientStructDataTaskEntity::getAvailableAt, now)
                .apply("attempt_count < max_attempts")
                .orderByAsc(PatientStructDataTaskEntity::getAvailableAt)
                .orderByAsc(PatientStructDataTaskEntity::getCreateTime)
                .last("OFFSET 0 ROWS FETCH NEXT " + batchSize + " ROWS ONLY");

        List<PatientStructDataTaskEntity> candidates = this.list(queryWrapper);
        List<PatientStructDataTaskEntity> claimed = new ArrayList<>();
        for (PatientStructDataTaskEntity candidate : candidates) {
            LambdaUpdateWrapper<PatientStructDataTaskEntity> claimWrapper = new LambdaUpdateWrapper<>();
            claimWrapper.eq(PatientStructDataTaskEntity::getId, candidate.getId())
                    .in(PatientStructDataTaskEntity::getStatus,
                        StructDataTaskStatus.PENDING.name(),
                        StructDataTaskStatus.FAILED.name())
                    .set(PatientStructDataTaskEntity::getStatus, StructDataTaskStatus.RUNNING.name())
                    .set(PatientStructDataTaskEntity::getLastStartTime, now)
                    .set(PatientStructDataTaskEntity::getUpdateTime, now)
                    .setSql("attempt_count = attempt_count + 1");
            if (this.update(claimWrapper)) {
                candidate.setStatus(StructDataTaskStatus.RUNNING.name());
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
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<PatientStructDataTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientStructDataTaskEntity::getId, taskId)
                .set(PatientStructDataTaskEntity::getStatus, StructDataTaskStatus.SUCCESS.name())
                .set(PatientStructDataTaskEntity::getLastFinishTime, now)
                .set(PatientStructDataTaskEntity::getReplayFromDate, null)
                .set(PatientStructDataTaskEntity::getLastErrorMessage, message)
                .set(PatientStructDataTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    @Override
    public void markFailed(Long taskId, String errorMessage) {
        if (taskId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<PatientStructDataTaskEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PatientStructDataTaskEntity::getId, taskId)
                .set(PatientStructDataTaskEntity::getStatus, StructDataTaskStatus.FAILED.name())
                .set(PatientStructDataTaskEntity::getLastFinishTime, now)
                .set(PatientStructDataTaskEntity::getAvailableAt,
                     now.plusSeconds(Math.max(1, structDataFormatProperties.getRetryDelaySeconds())))
                .set(PatientStructDataTaskEntity::getLastErrorMessage, errorMessage)
                .set(PatientStructDataTaskEntity::getUpdateTime, now);
        this.update(updateWrapper);
    }

    private LocalDate minDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }
}
