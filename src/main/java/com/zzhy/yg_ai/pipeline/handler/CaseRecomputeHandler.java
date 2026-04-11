package com.zzhy.yg_ai.pipeline.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.entity.InfectionAlertResultEntity;
import com.zzhy.yg_ai.domain.entity.InfectionCaseSnapshotEntity;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.enums.InfectionCaseState;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.model.InfectionEvidencePacket;
import com.zzhy.yg_ai.domain.model.JudgeDecisionResult;
import com.zzhy.yg_ai.pipeline.model.CaseRecomputeResult;
import com.zzhy.yg_ai.service.InfectionAlertResultService;
import com.zzhy.yg_ai.service.InfectionCaseSnapshotService;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.InfectionEvidencePacketBuilder;
import com.zzhy.yg_ai.service.InfectionJudgeService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class CaseRecomputeHandler extends AbstractTaskHandler<InfectionEventTaskEntity, CaseRecomputeResult> {

    private static final String TASK_NAME = "病例重算任务";
    private static final int CASE_MAX_WAIT_MINUTES = 30;

    private final InfectionEventTaskService infectionEventTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final InfectionEventPoolService infectionEventPoolService;
    private final InfectionCaseSnapshotService infectionCaseSnapshotService;
    private final InfectionEvidencePacketBuilder infectionEvidencePacketBuilder;
    private final InfectionJudgeService infectionJudgeService;
    private final InfectionAlertResultService infectionAlertResultService;
    private final ObjectMapper objectMapper;

    @Override
    protected CaseRecomputeResult process(InfectionEventTaskEntity taskEntity) {
        return processTask(taskEntity);
    }

    @Override
    protected void afterHandle(CaseRecomputeResult result) {
        finalizeTask(result);
    }

    private CaseRecomputeResult processTask(InfectionEventTaskEntity taskEntity) {
        List<Long> taskIds = extractTaskIds(taskEntity);
        String reqno = resolveReqno(taskEntity);
        if (!StringUtils.hasText(reqno)) {
            return new CaseRecomputeResult(taskIds, reqno, 0, 1, false, false, "reqno为空", "reqno为空");
        }

        try {
            InfectionCaseSnapshotEntity snapshot = infectionCaseSnapshotService.getOrInit(reqno);
            Long latestEventPoolVersion = infectionEventPoolService.getLatestActiveEventVersion(reqno);
            long snapshotVersion = snapshot == null || snapshot.getLastEventPoolVersion() == null
                    ? 0L : snapshot.getLastEventPoolVersion();
            if (latestEventPoolVersion == null || latestEventPoolVersion <= snapshotVersion) {
                return new CaseRecomputeResult(taskIds, reqno, 0, 0, true, false, null, "无新增事件版本，跳过病例重算");
            }

            LocalDateTime now = DateTimeUtils.now();
            if (taskEntity.getDebounceUntil() != null
                    && now.isBefore(taskEntity.getDebounceUntil())
                    && (taskEntity.getFirstTriggeredAt() == null
                    || now.isBefore(taskEntity.getFirstTriggeredAt().plusMinutes(CASE_MAX_WAIT_MINUTES)))) {
                infectionEventTaskService.reschedule(taskIds, taskEntity.getDebounceUntil(), "仍在病例重算防抖窗口内");
                return new CaseRecomputeResult(taskIds, reqno, 0, 0, false, true, null, "病例重算延后执行");
            }

            InfectionEvidencePacket packet = infectionEvidencePacketBuilder.build(reqno, now);
            JudgeDecisionResult decision = infectionJudgeService.judge(packet, now);
            persistAlertResult(snapshot, taskEntity, packet, decision);
            updateSnapshot(snapshot, latestEventPoolVersion, now, decision);
            return new CaseRecomputeResult(taskIds, reqno, 1, 0, false, false, null, "病例重算完成");
        } catch (Exception e) {
            log.error(buildFailureMessage(TASK_NAME,
                    "taskId", taskEntity == null ? null : taskEntity.getId(),
                    "reqno", reqno,
                    "message", "病例重算任务执行失败，需重试"), e);
            return new CaseRecomputeResult(taskIds, reqno, 0, 1, false, false, "病例重算任务执行失败，需重试", "病例重算任务执行失败，需重试");
        }
    }

    private void finalizeTask(CaseRecomputeResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }

        if (result.rescheduled()) {
            log.info("{}延后，{}", TASK_NAME, buildSummary(
                    "taskId", firstTaskId(result.taskIds()),
                    "reqno", result.reqno(),
                    "successCount", result.successCount(),
                    "failedCount", result.failedCount(),
                    "skipped", result.skipped(),
                    "rescheduled", result.rescheduled(),
                    "message", result.message()
            ));
            return;
        }

        if (result.skipped()) {
            infectionEventTaskService.markSkipped(result.taskIds(), result.message());
            log.info("{}跳过，{}", TASK_NAME, buildSummary(
                    "taskId", firstTaskId(result.taskIds()),
                    "reqno", result.reqno(),
                    "successCount", result.successCount(),
                    "failedCount", result.failedCount(),
                    "skipped", result.skipped(),
                    "rescheduled", result.rescheduled(),
                    "message", result.message()
            ));
            return;
        }

        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "病例重算失败";
            infectionEventTaskService.markFailed(result.taskIds(), message);
            String errorMessage = buildFailureMessage(TASK_NAME,
                    "taskId", firstTaskId(result.taskIds()),
                    "reqno", result.reqno(),
                    "message", message);
            infectionDailyJobLogService.log(InfectionJobStage.FINALIZE, InfectionJobStatus.ERROR, result.reqno(), errorMessage);
            return;
        }

        infectionEventTaskService.markSuccess(result.taskIds(), result.message());
        log.info("{}结束，{}", TASK_NAME, buildSummary(
                "taskId", firstTaskId(result.taskIds()),
                "reqno", result.reqno(),
                "successCount", result.successCount(),
                "failedCount", result.failedCount(),
                "skipped", result.skipped(),
                "rescheduled", result.rescheduled(),
                "message", result.message()
        ));
    }

    private List<Long> extractTaskIds(InfectionEventTaskEntity taskEntity) {
        if (taskEntity == null || taskEntity.getId() == null) {
            return List.of();
        }
        return List.of(taskEntity.getId());
    }

    private String resolveReqno(InfectionEventTaskEntity taskEntity) {
        if (taskEntity == null || !StringUtils.hasText(taskEntity.getReqno())) {
            return null;
        }
        return taskEntity.getReqno().trim();
    }

    private void persistAlertResult(InfectionCaseSnapshotEntity snapshot,
                                    InfectionEventTaskEntity taskEntity,
                                    InfectionEvidencePacket packet,
                                    JudgeDecisionResult decision) {
        InfectionAlertResultEntity result = new InfectionAlertResultEntity();
        result.setReqno(taskEntity.getReqno());
        result.setDataDate(taskEntity.getDataDate());
        result.setResultVersion(decision.resultVersion());
        result.setAlertStatus(decision.decisionStatus());
        result.setOverallRiskLevel(decision.warningLevel());
        result.setPrimarySite(decision.primarySite());
        result.setNewOnsetFlag(decision.newOnsetFlag());
        result.setAfter48hFlag(decision.after48hFlag());
        result.setProcedureRelatedFlag(decision.procedureRelatedFlag());
        result.setDeviceRelatedFlag(decision.deviceRelatedFlag());
        result.setInfectionPolarity(decision.infectionPolarity());
        result.setSourceSnapshotId(snapshot == null ? null : snapshot.getId());
        result.setResultJson(writeJson(decision));
        result.setDiffJson(writeJson(packet));
        infectionAlertResultService.saveResult(result);
    }

    private void updateSnapshot(InfectionCaseSnapshotEntity snapshot,
                                Long latestEventPoolVersion,
                                LocalDateTime judgeTime,
                                JudgeDecisionResult decision) {
        if (snapshot == null || decision == null) {
            return;
        }

        snapshot.setCaseState(decision.decisionStatus());
        snapshot.setWarningLevel(decision.warningLevel());
        snapshot.setPrimarySite(decision.primarySite());
        snapshot.setNosocomialLikelihood(decision.nosocomialLikelihood());
        snapshot.setCurrentNewOnsetFlag(decision.newOnsetFlag());
        snapshot.setCurrentAfter48hFlag(decision.after48hFlag());
        snapshot.setCurrentProcedureRelatedFlag(decision.procedureRelatedFlag());
        snapshot.setCurrentDeviceRelatedFlag(decision.deviceRelatedFlag());
        snapshot.setCurrentInfectionPolarity(decision.infectionPolarity());
        snapshot.setActiveEventKeysJson(writeJson(decision.newSupportingKeys()));
        snapshot.setActiveRiskKeysJson(writeJson(decision.newRiskKeys()));
        snapshot.setActiveAgainstKeysJson(writeJson(decision.newAgainstKeys()));
        snapshot.setLastJudgeTime(judgeTime);
        snapshot.setLastResultVersion(decision.resultVersion());
        snapshot.setLastEventPoolVersion(latestEventPoolVersion == null ? 0L : latestEventPoolVersion);
        snapshot.setJudgeDebounceUntil(null);

        InfectionCaseState state = InfectionCaseState.fromCodeOrDefault(decision.decisionStatus(), null);
        if (state == InfectionCaseState.CANDIDATE && snapshot.getLastCandidateSince() == null) {
            snapshot.setLastCandidateSince(judgeTime);
        }
        if (state == InfectionCaseState.WARNING && snapshot.getLastWarningSince() == null) {
            snapshot.setLastWarningSince(judgeTime);
        }
        if (state != null && !state.isActiveRisk()) {
            snapshot.setLastCandidateSince(null);
        }
        infectionCaseSnapshotService.saveOrUpdateSnapshot(snapshot);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

}
