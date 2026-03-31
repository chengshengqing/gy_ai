package com.zzhy.yg_ai.ai.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataCollectTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionJobStage;
import com.zzhy.yg_ai.domain.enums.InfectionJobStatus;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.domain.model.RawDataCollectResult;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
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
                infectionDailyJobLogService,
                infectionEvidenceBlockService,
                llmEventExtractorService,
                structDataFormatProperties,
                new ObjectMapper()
        );
    }

    @Test
    void processPendingRawDataTasksMarksCollectTaskSuccessAndUpdatesChangeTypes() {
        PatientRawDataCollectTaskEntity collectTask = new PatientRawDataCollectTaskEntity();
        collectTask.setId(1L);
        collectTask.setReqno("REQ-3001");
        when(patientRawDataCollectTaskService.claimPendingTasks(5)).thenReturn(List.of(collectTask));

        RawDataCollectResult result = new RawDataCollectResult();
        result.setReqno("REQ-3001");
        result.setStatus("success");
        result.setMessage("采集完成");
        result.setSavedDays(2);
        result.setChangeTypes("DIAGNOSIS");
        when(patientService.collectAndSaveRawDataResult("REQ-3001")).thenReturn(result);

        int processedCount = infectionPipeline.processPendingRawDataTasks();

        assertEquals(1, processedCount);
        verify(patientRawDataCollectTaskService).updateChangeTypes(1L, "DIAGNOSIS");
        verify(patientRawDataCollectTaskService).markSuccess(1L, "采集完成");
        verify(patientRawDataCollectTaskService, never()).markFailed(any(), any());
    }

    @Test
    void processPendingRawDataTasksMarksCollectTaskFailedWhenCollectionThrows() {
        PatientRawDataCollectTaskEntity collectTask = new PatientRawDataCollectTaskEntity();
        collectTask.setId(2L);
        collectTask.setReqno("REQ-3002");
        when(patientRawDataCollectTaskService.claimPendingTasks(5)).thenReturn(List.of(collectTask));
        when(patientService.collectAndSaveRawDataResult("REQ-3002")).thenThrow(new RuntimeException("source unavailable"));

        int processedCount = infectionPipeline.processPendingRawDataTasks();

        assertEquals(0, processedCount);
        verify(patientRawDataCollectTaskService).updateChangeTypes(2L, null);
        verify(patientRawDataCollectTaskService).markFailed(2L, "source unavailable");
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LOAD,
                InfectionJobStatus.ERROR,
                "REQ-3002",
                "source unavailable"
        );
        verify(patientRawDataCollectTaskService, never()).markSuccess(any(), any());
    }

    @Test
    void processPendingStructDataConsumesGroupedChangeTasksAndMarksSuccess() {
        LocalDate firstDate = LocalDate.of(2026, 3, 25);
        LocalDate secondDate = LocalDate.of(2026, 3, 27);
        LocalDateTime firstVersion = LocalDateTime.of(2026, 3, 29, 10, 0);
        LocalDateTime secondVersion = LocalDateTime.of(2026, 3, 29, 10, 5);

        PatientRawDataChangeTaskEntity firstChange = buildChangeTask(11L, 101L, "REQ-4001", firstDate, firstVersion);
        PatientRawDataChangeTaskEntity secondChange = buildChangeTask(12L, 102L, "REQ-4001", secondDate, secondVersion);
        when(patientRawDataChangeTaskService.claimPendingStructTasks(5)).thenReturn(List.of(firstChange, secondChange));

        PatientRawDataEntity firstRaw = buildRawData(101L, "REQ-4001", firstDate, firstVersion);
        PatientRawDataEntity secondRaw = buildRawData(102L, "REQ-4001", secondDate, secondVersion);
        when(patientService.getRawDataById(101L)).thenReturn(firstRaw);
        when(patientService.getRawDataById(102L)).thenReturn(secondRaw);
        when(patientService.listPendingStructRawData("REQ-4001", firstDate)).thenReturn(List.of(firstRaw, secondRaw));
        when(summaryAgent.extractDailyIllness(any(), eq(firstRaw)))
                .thenReturn(new SummaryAgent.DailyIllnessResult("{\"row\":1}", "{\"timeline\":[]}"));
        when(summaryAgent.extractDailyIllness(any(), eq(secondRaw)))
                .thenReturn(new SummaryAgent.DailyIllnessResult("{\"row\":2}", "{\"timeline\":[]}"));

        int formattedCount = infectionPipeline.processPendingStructData();

        assertEquals(2, formattedCount);
        verify(patientService).resetDerivedData("REQ-4001", firstDate);
        verify(patientService).listPendingStructRawData("REQ-4001", firstDate);
        verify(patientService).saveStructDataJson(101L, "{\"row\":1}", null);
        verify(patientService).saveStructDataJson(102L, "{\"row\":2}", null);
        verify(patientRawDataChangeTaskService).markStructSuccess(List.of(11L, 12L), "结构化处理成功，successCount=2", true);
        verify(patientRawDataChangeTaskService, never()).markStructFailed(any(), any());
    }

    @Test
    void processPendingStructDataSkipsStaleChangesAndMarksSuccessWithoutReplay() {
        LocalDateTime oldVersion = LocalDateTime.of(2026, 3, 29, 9, 0);
        LocalDateTime newVersion = LocalDateTime.of(2026, 3, 29, 10, 0);

        PatientRawDataChangeTaskEntity staleChange = buildChangeTask(21L, 201L, "REQ-5001", LocalDate.of(2026, 3, 26), oldVersion);
        when(patientRawDataChangeTaskService.claimPendingStructTasks(5)).thenReturn(List.of(staleChange));

        PatientRawDataEntity currentRaw = buildRawData(201L, "REQ-5001", LocalDate.of(2026, 3, 26), newVersion);
        when(patientService.getRawDataById(201L)).thenReturn(currentRaw);

        int formattedCount = infectionPipeline.processPendingStructData();

        assertEquals(0, formattedCount);
        verify(patientService, never()).resetDerivedData(any(), any());
        verify(patientService, never()).listPendingStructRawData(any(), any());
        verify(patientRawDataChangeTaskService).markStructSuccess(List.of(21L), "无待处理结构化数据", false);
    }

    @Test
    void processPendingStructDataMarksFailedWhenSummaryGenerationFails() {
        LocalDate replayDate = LocalDate.of(2026, 3, 24);
        LocalDateTime version = LocalDateTime.of(2026, 3, 29, 11, 0);

        PatientRawDataChangeTaskEntity changeTask = buildChangeTask(31L, 301L, "REQ-6001", replayDate, version);
        PatientRawDataEntity rawData = buildRawData(301L, "REQ-6001", replayDate, version);
        when(patientRawDataChangeTaskService.claimPendingStructTasks(5)).thenReturn(List.of(changeTask));
        when(patientService.getRawDataById(301L)).thenReturn(rawData);
        when(patientService.listPendingStructRawData("REQ-6001", replayDate)).thenReturn(List.of(rawData));
        when(summaryAgent.extractDailyIllness(any(), eq(rawData))).thenThrow(new RuntimeException("LLM error"));

        int formattedCount = infectionPipeline.processPendingStructData();

        assertEquals(0, formattedCount);
        verify(patientService).resetDerivedData("REQ-6001", replayDate);
        verify(patientRawDataChangeTaskService).markStructFailed(List.of(31L), "存在未完成的 rawData 行，需重试");
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.NORMALIZE,
                InfectionJobStatus.ERROR,
                "REQ-6001",
                "存在未完成的 rawData 行，需重试"
        );
    }

    @Test
    void processPendingEventDataTriggersEventExtractionAfterSummaryBuilt() {
        LocalDate replayDate = LocalDate.of(2026, 3, 28);
        LocalDateTime version = LocalDateTime.of(2026, 3, 30, 9, 0);

        PatientRawDataChangeTaskEntity changeTask = buildChangeTask(41L, 401L, "REQ-7001", replayDate, version);
        PatientRawDataEntity rawData = buildRawData(401L, "REQ-7001", replayDate, version);
        PatientSummaryEntity latestSummary = new PatientSummaryEntity();
        latestSummary.setReqno("REQ-7001");
        latestSummary.setSummaryJson("{\"timeline\":[]}");
        when(patientRawDataChangeTaskService.claimPendingEventTasks(5)).thenReturn(List.of(changeTask));
        when(patientService.getRawDataById(401L)).thenReturn(rawData);
        when(patientService.getLatestSummary("REQ-7001")).thenReturn(latestSummary);
        when(patientService.listPendingEventRawData("REQ-7001", replayDate)).thenReturn(List.of(rawData));
        when(infectionEvidenceBlockService.buildBlocks(any(), any())).thenReturn(nonEmptyBuildResult());
        when(llmEventExtractorService.extractAndSave(any()))
                .thenReturn(new LlmEventExtractorResult("{\"events\":[{\"event_key\":\"k1\"}]}", List.of(), List.of(), 1));

        int formattedCount = infectionPipeline.processPendingEventData();

        assertEquals(1, formattedCount);
        verify(infectionEvidenceBlockService).buildBlocks(any(), any());
        verify(llmEventExtractorService).extractAndSave(any());
        verify(patientService).saveEventJson(401L, "{\"events\":[{\"event_key\":\"k1\"}]}");
        verify(patientRawDataChangeTaskService).markEventSuccess(List.of(41L), "事件抽取成功，successCount=1");
    }

    @Test
    void processPendingEventDataMarksFailedWhenEventExtractionFails() {
        LocalDate replayDate = LocalDate.of(2026, 3, 28);
        LocalDateTime version = LocalDateTime.of(2026, 3, 30, 9, 0);

        PatientRawDataChangeTaskEntity changeTask = buildChangeTask(51L, 501L, "REQ-7002", replayDate, version);
        PatientRawDataEntity rawData = buildRawData(501L, "REQ-7002", replayDate, version);
        PatientSummaryEntity latestSummary = new PatientSummaryEntity();
        latestSummary.setReqno("REQ-7002");
        latestSummary.setSummaryJson("{\"timeline\":[]}");
        when(patientRawDataChangeTaskService.claimPendingEventTasks(5)).thenReturn(List.of(changeTask));
        when(patientService.getRawDataById(501L)).thenReturn(rawData);
        when(patientService.getLatestSummary("REQ-7002")).thenReturn(latestSummary);
        when(patientService.listPendingEventRawData("REQ-7002", replayDate)).thenReturn(List.of(rawData));
        when(infectionEvidenceBlockService.buildBlocks(any(), any())).thenReturn(nonEmptyBuildResult());
        when(llmEventExtractorService.extractAndSave(any())).thenThrow(new RuntimeException("extract failed"));

        int formattedCount = infectionPipeline.processPendingEventData();

        assertEquals(0, formattedCount);
        verify(patientRawDataChangeTaskService).markEventFailed(List.of(51L), "存在未完成的事件抽取 rawData 行，需重试");
        verify(infectionDailyJobLogService).log(
                InfectionJobStage.LLM,
                InfectionJobStatus.ERROR,
                "REQ-7002",
                "存在未完成的事件抽取 rawData 行，需重试"
        );
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
                LocalDate.of(2026, 3, 28),
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
