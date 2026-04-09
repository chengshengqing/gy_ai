package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionEventTriggerReasonCode;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.enums.PatientCourseDataType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.pipeline.model.EventExtractResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.LlmEventExtractorService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventExtractHandler extends AbstractTaskHandler<InfectionEventTaskEntity, EventExtractResult> {

    private static final int CASE_RECOMPUTE_PRIORITY = 10;

    private final PatientService patientService;
    private final InfectionEventTaskService infectionEventTaskService;
    private final InfectionDailyJobLogService infectionDailyJobLogService;
    private final InfectionEvidenceBlockService infectionEvidenceBlockService;
    private final LlmEventExtractorService llmEventExtractorService;

    @Override
    protected EventExtractResult process(InfectionEventTaskEntity taskEntity) {
        return processTask(taskEntity);
    }

    @Override
    protected void afterHandle(EventExtractResult result) {
        finalizeTask(result);
    }

    private EventExtractResult processTask(InfectionEventTaskEntity taskEntity) {
        List<Long> taskIds = extractTaskIds(taskEntity);
        String reqno = resolveReqno(taskEntity);
        if (!StringUtils.hasText(reqno)) {
            return new EventExtractResult(taskIds, reqno, 0, 1, 0, "reqno为空", false, "reqno为空");
        }

        PatientRawDataEntity rawData = collectFreshRawData(taskEntity);
        if (rawData == null) {
            return new EventExtractResult(taskIds, reqno, 0, 0, 0, null, true, "事件任务版本已过期，跳过");
        }

        try {
            String timelineWindowJson = patientService.buildSummaryWindowJson(reqno, rawData.getDataDate());
            LlmEventExtractorResult extractResult = extractEvents(rawData, timelineWindowJson, taskEntity);
            int caseTaskCount = 0;
            if (extractResult != null && !extractResult.persistedEvents().isEmpty()) {
                infectionEventTaskService.upsertCaseRecomputeTask(
                        reqno,
                        rawData.getId(),
                        rawData.getDataDate(),
                        rawData.getLastTime(),
                        taskEntity.getSourceBatchTime() == null ? LocalDateTime.now() : taskEntity.getSourceBatchTime(),
                        0,
                        CASE_RECOMPUTE_PRIORITY
                );
                caseTaskCount = 1;
            }
            String message = caseTaskCount > 0
                    ? "事件抽取成功，已创建caseTask=" + caseTaskCount
                    : "事件抽取成功";
            return new EventExtractResult(taskIds, reqno, 1, 0, caseTaskCount, null, false, message);
        } catch (Exception e) {
            log.error("事件抽取失败，taskId={}, rowId={}, reqno={}",
                    taskEntity == null ? null : taskEntity.getId(),
                    rawData.getId(),
                    reqno,
                    e);
            return new EventExtractResult(taskIds, reqno, 0, 1, 0, "存在未完成的事件抽取 rawData 行，需重试", false, "存在未完成的事件抽取 rawData 行，需重试");
        }
    }

    private void finalizeTask(EventExtractResult result) {
        if (result == null || result.taskIds() == null || result.taskIds().isEmpty()) {
            return;
        }

        if (result.failedCount() > 0) {
            String message = StringUtils.hasText(result.lastErrorMessage())
                    ? result.lastErrorMessage()
                    : "部分事件抽取失败";
            infectionEventTaskService.markFailed(result.taskIds(), message);
            infectionDailyJobLogService.log(InfectionJobStage.LLM, InfectionJobStatus.ERROR, result.reqno(), message);
            return;
        }

        if (result.skipped()) {
            infectionEventTaskService.markSkipped(result.taskIds(), result.message());
            return;
        }

        infectionEventTaskService.markSuccess(result.taskIds(), result.message());
        log.info("事件抽取完成，reqno={}, successCount={}, caseTaskCount={}",
                result.reqno(), result.successCount(), result.caseTaskCount());
    }

    private LlmEventExtractorResult extractEvents(PatientRawDataEntity rawData,
                                                  String timelineWindowJson,
                                                  InfectionEventTaskEntity taskEntity) {
        EvidenceBlockBuildResult buildResult = infectionEvidenceBlockService.buildBlocks(rawData, timelineWindowJson);
        if (buildResult == null || buildResult.primaryBlocks().isEmpty()) {
            return null;
        }

        List<EvidenceBlock> primaryBlocks = selectPrimaryBlocks(buildResult, taskEntity);
        if (primaryBlocks.isEmpty()) {
            return null;
        }
        return llmEventExtractorService.extractAndSave(buildResult, primaryBlocks);
    }

    private List<EvidenceBlock> selectPrimaryBlocks(EvidenceBlockBuildResult buildResult, InfectionEventTaskEntity taskEntity) {
        if (buildResult == null) {
            return List.of();
        }

        Set<InfectionEventTriggerReasonCode> triggerReasons = InfectionEventTriggerReasonCode.fromCsv(
                taskEntity == null ? null : taskEntity.getTriggerReasonCodes()
        );
        EnumSet<PatientCourseDataType> changedTypes = PatientCourseDataType.parseCsv(
                taskEntity == null ? null : taskEntity.getChangedTypes()
        );
        if (triggerReasons.isEmpty() && changedTypes.isEmpty()) {
            return buildResult.primaryBlocks();
        }

        boolean includeStructuredFact = hasAny(triggerReasons,
                InfectionEventTriggerReasonCode.LAB_RESULT_CHANGED,
                InfectionEventTriggerReasonCode.MICROBE_CHANGED,
                InfectionEventTriggerReasonCode.IMAGING_CHANGED,
                InfectionEventTriggerReasonCode.ANTIBIOTIC_OR_ORDER_CHANGED,
                InfectionEventTriggerReasonCode.OPERATION_CHANGED,
                InfectionEventTriggerReasonCode.TRANSFER_CHANGED,
                InfectionEventTriggerReasonCode.VITAL_SIGN_CHANGED)
                || hasAny(changedTypes,
                PatientCourseDataType.FULL_PATIENT,
                PatientCourseDataType.DIAGNOSIS,
                PatientCourseDataType.BODY_SURFACE,
                PatientCourseDataType.DOCTOR_ADVICE,
                PatientCourseDataType.LAB_TEST,
                PatientCourseDataType.USE_MEDICINE,
                PatientCourseDataType.VIDEO_RESULT,
                PatientCourseDataType.TRANSFER,
                PatientCourseDataType.OPERATION,
                PatientCourseDataType.MICROBE);
        boolean includeClinicalText = triggerReasons.contains(InfectionEventTriggerReasonCode.ILLNESS_COURSE_CHANGED)
                || hasAny(changedTypes, PatientCourseDataType.FULL_PATIENT, PatientCourseDataType.ILLNESS_COURSE);
        boolean includeMidSemantic = includeClinicalText
                || hasAny(triggerReasons,
                InfectionEventTriggerReasonCode.ANTIBIOTIC_OR_ORDER_CHANGED,
                InfectionEventTriggerReasonCode.OPERATION_CHANGED,
                InfectionEventTriggerReasonCode.TRANSFER_CHANGED)
                || hasAny(changedTypes,
                PatientCourseDataType.FULL_PATIENT,
                PatientCourseDataType.ILLNESS_COURSE,
                PatientCourseDataType.DOCTOR_ADVICE,
                PatientCourseDataType.USE_MEDICINE,
                PatientCourseDataType.TRANSFER,
                PatientCourseDataType.OPERATION);

        List<EvidenceBlock> selected = new ArrayList<>();
        if (includeStructuredFact) {
            selected.addAll(filterByBlockType(buildResult.structuredFactBlocks(), EvidenceBlockType.STRUCTURED_FACT));
        }
        if (includeClinicalText) {
            selected.addAll(filterByBlockType(buildResult.clinicalTextBlocks(), EvidenceBlockType.CLINICAL_TEXT));
        }
        if (includeMidSemantic) {
            selected.addAll(filterByBlockType(buildResult.midSemanticBlocks(), EvidenceBlockType.MID_SEMANTIC));
        }
        if (selected.isEmpty()) {
            return buildResult.primaryBlocks();
        }
        return List.copyOf(selected);
    }

    private boolean hasAny(Set<InfectionEventTriggerReasonCode> triggerReasons, InfectionEventTriggerReasonCode... expected) {
        if (triggerReasons == null || triggerReasons.isEmpty()) {
            return false;
        }
        for (InfectionEventTriggerReasonCode code : expected) {
            if (code != null && triggerReasons.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAny(EnumSet<PatientCourseDataType> changedTypes, PatientCourseDataType... expected) {
        if (changedTypes == null || changedTypes.isEmpty()) {
            return false;
        }
        for (PatientCourseDataType type : expected) {
            if (type != null && changedTypes.contains(type)) {
                return true;
            }
        }
        return false;
    }

    private List<EvidenceBlock> filterByBlockType(List<EvidenceBlock> blocks, EvidenceBlockType blockType) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> result = new ArrayList<>();
        for (EvidenceBlock block : blocks) {
            if (block != null && block.blockType() == blockType) {
                result.add(block);
            }
        }
        return result;
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

    private PatientRawDataEntity collectFreshRawData(InfectionEventTaskEntity taskEntity) {
        if (taskEntity == null || taskEntity.getPatientRawDataId() == null || taskEntity.getRawDataLastTime() == null) {
            return null;
        }
        PatientRawDataEntity rawData = patientService.getRawDataById(taskEntity.getPatientRawDataId());
        if (rawData == null || rawData.getLastTime() == null) {
            return null;
        }
        if (!rawData.getLastTime().equals(taskEntity.getRawDataLastTime())) {
            return null;
        }
        return rawData;
    }
}
