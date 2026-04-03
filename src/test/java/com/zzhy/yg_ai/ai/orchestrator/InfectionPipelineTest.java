package com.zzhy.yg_ai.ai.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.LlmEventExtractorService;
import com.zzhy.yg_ai.service.PatientRawDataChangeTaskService;
import com.zzhy.yg_ai.service.PatientRawDataCollectTaskService;
import com.zzhy.yg_ai.service.PatientService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfectionPipelineTest {

    @Mock
    private SummaryAgent summaryAgent;

    @Mock
    private PatientService patientService;

    @Mock
    private PatientRawDataCollectTaskService patientRawDataCollectTaskService;

    @Mock
    private PatientRawDataChangeTaskService patientRawDataChangeTaskService;

    @Mock
    private InfectionEventTaskService infectionEventTaskService;

    @Mock
    private InfectionDailyJobLogService infectionDailyJobLogService;

    @Mock
    private InfectionEvidenceBlockService infectionEvidenceBlockService;

    @Mock
    private LlmEventExtractorService llmEventExtractorService;

    private InfectionPipeline infectionPipeline;

    @BeforeEach
    void setUp() {
        InfectionMonitorProperties infectionMonitorProperties = new InfectionMonitorProperties();
        infectionMonitorProperties.setBatchSize(5);
        infectionMonitorProperties.setWorkerThreads(1);

        StructDataFormatProperties structDataFormatProperties = new StructDataFormatProperties();
        structDataFormatProperties.setBatchSize(5);
        structDataFormatProperties.setWorkerThreads(1);

        infectionPipeline = new InfectionPipeline(
                infectionMonitorProperties,
                summaryAgent,
                patientService,
                patientRawDataCollectTaskService,
                patientRawDataChangeTaskService,
                infectionEventTaskService,
                infectionDailyJobLogService,
                infectionEvidenceBlockService,
                llmEventExtractorService,
                structDataFormatProperties
        );
    }

    @Test
    void processPendingRawDataTasksMarksCollectTaskSuccessAndUpdatesChangeTypes() {
        PatientRawDataCollectTaskEntity collectTask = new PatientRawDataCollectTaskEntity();
        collectTask.setId(1L);
        collectTask.setReqno("REQ-3001");
        collectTask.setPreviousSourceLastTime(LocalDateTime.of(2026, 4, 3, 0, 0));
        collectTask.setSourceLastTime(LocalDateTime.of(2026, 4, 3, 3, 0));
        when(patientRawDataCollectTaskService.claimPendingTasks(5)).thenReturn(List.of(collectTask));

        RawDataCollectResult result = new RawDataCollectResult();
        result.setReqno("REQ-3001");
        result.setStatus("success");
        result.setMessage("采集完成");
        result.setSavedDays(2);
        result.setChangeTypes("ILLNESS_COURSE");
        when(patientService.collectAndSaveRawDataResult(
                "REQ-3001",
                LocalDateTime.of(2026, 4, 3, 0, 0),
                LocalDateTime.of(2026, 4, 3, 3, 0)
        )).thenReturn(result);

        int processedCount = infectionPipeline.processPendingRawDataTasks();

        assertEquals(1, processedCount);
        verify(patientRawDataCollectTaskService).updateChangeTypes(1L, "ILLNESS_COURSE");
        verify(patientRawDataCollectTaskService).markSuccess(1L, "采集完成");
    }

    @Test
    void processPendingStructDataMarksSuccessWhenRowsFormatted() {
        LocalDate dataDate = LocalDate.of(2026, 4, 3);
        LocalDateTime version = LocalDateTime.of(2026, 4, 3, 3, 5);
        PatientRawDataChangeTaskEntity changeTask = buildChangeTask(11L, 101L, "REQ-4001", dataDate, version);
        PatientRawDataEntity rawData = buildRawData(101L, "REQ-4001", dataDate, version);
        when(patientRawDataChangeTaskService.claimPendingStructTasks(5)).thenReturn(List.of(changeTask));
        when(patientService.getRawDataById(101L)).thenReturn(rawData);
        when(summaryAgent.extractDailyIllness(rawData))
                .thenReturn(new SummaryAgent.DailyIllnessResult("{\"row\":1}", "{\"timeline\":[]}"));

        int formattedCount = infectionPipeline.processPendingStructData();

        assertEquals(1, formattedCount);
        verify(patientService).resetDerivedDataForRawData(101L);
        verify(patientService).saveStructDataJson(101L, "{\"row\":1}", "{\"timeline\":[]}");
        verify(patientRawDataChangeTaskService).markStructSuccess(List.of(11L), "结构化处理成功，successCount=1");
    }

    @Test
    void processPendingStructDataMarksSkippedWhenTaskVersionExpired() {
        LocalDate dataDate = LocalDate.of(2026, 4, 3);
        LocalDateTime taskVersion = LocalDateTime.of(2026, 4, 3, 3, 5);
        LocalDateTime latestVersion = LocalDateTime.of(2026, 4, 3, 4, 1);
        PatientRawDataChangeTaskEntity changeTask = buildChangeTask(12L, 102L, "REQ-4002", dataDate, taskVersion);
        PatientRawDataEntity rawData = buildRawData(102L, "REQ-4002", dataDate, latestVersion);
        when(patientRawDataChangeTaskService.claimPendingStructTasks(5)).thenReturn(List.of(changeTask));
        when(patientService.getRawDataById(102L)).thenReturn(rawData);

        int formattedCount = infectionPipeline.processPendingStructData();

        assertEquals(0, formattedCount);
        verify(patientRawDataChangeTaskService).markStructSkipped(List.of(12L), "结构化任务版本已过期，跳过");
        verify(summaryAgent, never()).extractDailyIllness(any());
    }

    @Test
    void processPendingEventDataConsumesIndependentEventTaskAndCreatesCaseTask() {
        LocalDate dataDate = LocalDate.of(2026, 4, 3);
        LocalDateTime version = LocalDateTime.of(2026, 4, 3, 3, 10);
        InfectionEventTaskEntity taskEntity = new InfectionEventTaskEntity();
        taskEntity.setId(21L);
        taskEntity.setTaskType(InfectionEventTaskType.EVENT_EXTRACT.name());
        taskEntity.setReqno("REQ-5001");
        taskEntity.setPatientRawDataId(201L);
        taskEntity.setDataDate(dataDate);
        taskEntity.setRawDataLastTime(version);
        taskEntity.setSourceBatchTime(LocalDateTime.of(2026, 4, 3, 3, 0));
        when(infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, 5))
                .thenReturn(List.of(taskEntity));

        PatientRawDataEntity rawData = buildRawData(201L, "REQ-5001", dataDate, version);
        when(patientService.getRawDataById(201L)).thenReturn(rawData);
        when(patientService.buildSummaryWindowJson("REQ-5001", dataDate, 7)).thenReturn("{\"changes\":[]}");
        when(infectionEvidenceBlockService.buildBlocks(rawData, "{\"changes\":[]}")).thenReturn(nonEmptyBuildResult());
        when(llmEventExtractorService.extractAndSave(any()))
                .thenReturn(new LlmEventExtractorResult("{\"events\":[{\"event_key\":\"k1\"}]}", List.of(), List.of(new com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity()), 1));

        int extractedCount = infectionPipeline.processPendingEventData();

        assertEquals(1, extractedCount);
        verify(infectionEventTaskService).upsertCaseRecomputeTask(
                "REQ-5001",
                201L,
                dataDate,
                version,
                LocalDateTime.of(2026, 4, 3, 3, 0),
                30,
                10
        );
        verify(infectionEventTaskService).markSuccess(List.of(21L), "事件抽取成功，已创建caseTask=1");
    }

    @Test
    void processPendingCaseDataMarksSuccessForPlaceholderCaseTask() {
        InfectionEventTaskEntity taskEntity = new InfectionEventTaskEntity();
        taskEntity.setId(31L);
        taskEntity.setTaskType(InfectionEventTaskType.CASE_RECOMPUTE.name());
        taskEntity.setReqno("REQ-6001");
        when(infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.CASE_RECOMPUTE, 5))
                .thenReturn(List.of(taskEntity));

        int processedCount = infectionPipeline.processPendingCaseData();

        assertEquals(1, processedCount);
        verify(infectionEventTaskService).markSuccess(List.of(31L), "病例重算占位任务完成");
    }

    @Test
    void processPendingEventDataMarksFailedWhenExtractorThrows() {
        LocalDate dataDate = LocalDate.of(2026, 4, 3);
        LocalDateTime version = LocalDateTime.of(2026, 4, 3, 3, 10);
        InfectionEventTaskEntity taskEntity = new InfectionEventTaskEntity();
        taskEntity.setId(41L);
        taskEntity.setTaskType(InfectionEventTaskType.EVENT_EXTRACT.name());
        taskEntity.setReqno("REQ-7001");
        taskEntity.setPatientRawDataId(401L);
        taskEntity.setDataDate(dataDate);
        taskEntity.setRawDataLastTime(version);
        when(infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, 5))
                .thenReturn(List.of(taskEntity));
        when(patientService.getRawDataById(401L)).thenReturn(buildRawData(401L, "REQ-7001", dataDate, version));
        when(patientService.buildSummaryWindowJson("REQ-7001", dataDate, 7)).thenReturn("{}");
        when(infectionEvidenceBlockService.buildBlocks(any(), any())).thenReturn(nonEmptyBuildResult());
        when(llmEventExtractorService.extractAndSave(any())).thenThrow(new RuntimeException("extract failed"));

        int extractedCount = infectionPipeline.processPendingEventData();

        assertEquals(0, extractedCount);
        verify(infectionEventTaskService).markFailed(List.of(41L), "存在未完成的事件抽取 rawData 行，需重试");
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LLM,
                InfectionJobStatus.ERROR,
                "REQ-7001",
                "存在未完成的事件抽取 rawData 行，需重试"
        );
        verify(infectionEventTaskService, never()).markSuccess(any(), any());
    }

    private PatientRawDataChangeTaskEntity buildChangeTask(Long id,
                                                           Long rawDataId,
                                                           String reqno,
                                                           LocalDate dataDate,
                                                           LocalDateTime version) {
        PatientRawDataChangeTaskEntity changeTask = new PatientRawDataChangeTaskEntity();
        changeTask.setId(id);
        changeTask.setPatientRawDataId(rawDataId);
        changeTask.setReqno(reqno);
        changeTask.setDataDate(dataDate);
        changeTask.setRawDataLastTime(version);
        return changeTask;
    }

    private PatientRawDataEntity buildRawData(Long id, String reqno, LocalDate dataDate, LocalDateTime version) {
        PatientRawDataEntity rawData = new PatientRawDataEntity();
        rawData.setId(id);
        rawData.setReqno(reqno);
        rawData.setDataDate(dataDate);
        rawData.setLastTime(version);
        rawData.setDataJson("{\"data\":true}");
        rawData.setFilterDataJson("{\"filtered\":true}");
        return rawData;
    }

    private EvidenceBlockBuildResult nonEmptyBuildResult() {
        EvidenceBlock block = new EvidenceBlock(
                "block-1",
                "REQ-7001",
                401L,
                LocalDate.of(2026, 4, 3),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-1",
                "病程记录",
                "{\"note_text\":\"考虑感染\"}",
                false
        );
        return new EvidenceBlockBuildResult(List.of(), List.of(block), List.of(), List.of());
    }
}
