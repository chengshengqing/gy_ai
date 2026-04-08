package com.zzhy.yg_ai.ai.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.agent.SummaryAgent;
import com.zzhy.yg_ai.config.InfectionMonitorProperties;
import com.zzhy.yg_ai.config.StructDataFormatProperties;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionEventTaskType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.service.InfectionAlertResultService;
import com.zzhy.yg_ai.service.InfectionCaseSnapshotService;
import com.zzhy.yg_ai.service.InfectionDailyJobLogService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import com.zzhy.yg_ai.service.InfectionEventTaskService;
import com.zzhy.yg_ai.service.InfectionEvidenceBlockService;
import com.zzhy.yg_ai.service.InfectionEvidencePacketBuilder;
import com.zzhy.yg_ai.service.InfectionJudgeService;
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
import org.mockito.ArgumentCaptor;
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
    @Mock
    private InfectionEventPoolService infectionEventPoolService;
    @Mock
    private InfectionCaseSnapshotService infectionCaseSnapshotService;
    @Mock
    private InfectionEvidencePacketBuilder infectionEvidencePacketBuilder;
    @Mock
    private InfectionJudgeService infectionJudgeService;
    @Mock
    private InfectionAlertResultService infectionAlertResultService;

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
                infectionEventPoolService,
                infectionCaseSnapshotService,
                infectionEvidencePacketBuilder,
                infectionJudgeService,
                infectionAlertResultService,
                new ObjectMapper(),
                structDataFormatProperties
        );
    }

    @Test
    void processPendingEventDataRoutesClinicalTextAndMidSemanticForIllnessCourseChange() {
        InfectionEventTaskEntity taskEntity = buildEventTask("ILLNESS_COURSE_CHANGED", null);
        PatientRawDataEntity rawData = buildRawData(taskEntity);
        when(infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, 5))
                .thenReturn(List.of(taskEntity));
        when(patientService.getRawDataById(201L)).thenReturn(rawData);
        when(patientService.buildSummaryWindowJson("REQ-5001", taskEntity.getDataDate())).thenReturn("{\"changes\":[]}");
        when(infectionEvidenceBlockService.buildBlocks(rawData, "{\"changes\":[]}")).thenReturn(buildResultWithAllPrimaryBlocks());
        when(llmEventExtractorService.extractAndSave(any(), any()))
                .thenReturn(new LlmEventExtractorResult(null, List.of(), List.of(), 2));

        int extractedCount = infectionPipeline.processPendingEventData();

        assertEquals(1, extractedCount);
        ArgumentCaptor<List<EvidenceBlock>> blocksCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmEventExtractorService).extractAndSave(any(), blocksCaptor.capture());
        List<EvidenceBlock> selected = blocksCaptor.getValue();
        assertEquals(List.of(EvidenceBlockType.CLINICAL_TEXT, EvidenceBlockType.MID_SEMANTIC),
                selected.stream().map(EvidenceBlock::blockType).toList());
        verify(infectionEventTaskService).markSuccess(List.of(21L), "事件抽取成功");
        verify(infectionEventTaskService, never()).upsertCaseRecomputeTask(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void processPendingEventDataUsesChangedTypesWhenTriggerReasonsMissing() {
        InfectionEventTaskEntity taskEntity = buildEventTask(null, "LAB_TEST");
        PatientRawDataEntity rawData = buildRawData(taskEntity);
        when(infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, 5))
                .thenReturn(List.of(taskEntity));
        when(patientService.getRawDataById(201L)).thenReturn(rawData);
        when(patientService.buildSummaryWindowJson("REQ-5001", taskEntity.getDataDate())).thenReturn("{\"changes\":[]}");
        when(infectionEvidenceBlockService.buildBlocks(rawData, "{\"changes\":[]}")).thenReturn(buildResultWithAllPrimaryBlocks());
        when(llmEventExtractorService.extractAndSave(any(), any()))
                .thenReturn(new LlmEventExtractorResult(null, List.of(), List.of(), 1));

        int extractedCount = infectionPipeline.processPendingEventData();

        assertEquals(1, extractedCount);
        ArgumentCaptor<List<EvidenceBlock>> blocksCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmEventExtractorService).extractAndSave(any(), blocksCaptor.capture());
        List<EvidenceBlock> selected = blocksCaptor.getValue();
        assertEquals(1, selected.size());
        assertEquals(EvidenceBlockType.STRUCTURED_FACT, selected.get(0).blockType());
    }

    @Test
    void processPendingEventDataFallsBackToAllPrimaryBlocksWhenNoRoutingFieldsPresent() {
        InfectionEventTaskEntity taskEntity = buildEventTask(null, null);
        PatientRawDataEntity rawData = buildRawData(taskEntity);
        when(infectionEventTaskService.claimPendingTasks(InfectionEventTaskType.EVENT_EXTRACT, 5))
                .thenReturn(List.of(taskEntity));
        when(patientService.getRawDataById(201L)).thenReturn(rawData);
        when(patientService.buildSummaryWindowJson("REQ-5001", taskEntity.getDataDate())).thenReturn("{\"changes\":[]}");
        when(infectionEvidenceBlockService.buildBlocks(rawData, "{\"changes\":[]}")).thenReturn(buildResultWithAllPrimaryBlocks());
        when(llmEventExtractorService.extractAndSave(any(), any()))
                .thenReturn(new LlmEventExtractorResult(null, List.of(), List.of(), 3));

        infectionPipeline.processPendingEventData();

        ArgumentCaptor<List<EvidenceBlock>> blocksCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmEventExtractorService).extractAndSave(any(), blocksCaptor.capture());
        assertEquals(List.of(
                        EvidenceBlockType.STRUCTURED_FACT,
                        EvidenceBlockType.CLINICAL_TEXT,
                        EvidenceBlockType.MID_SEMANTIC
                ),
                blocksCaptor.getValue().stream().map(EvidenceBlock::blockType).toList());
    }

    private InfectionEventTaskEntity buildEventTask(String triggerReasonCodes, String changedTypes) {
        InfectionEventTaskEntity taskEntity = new InfectionEventTaskEntity();
        taskEntity.setId(21L);
        taskEntity.setTaskType(InfectionEventTaskType.EVENT_EXTRACT.name());
        taskEntity.setReqno("REQ-5001");
        taskEntity.setPatientRawDataId(201L);
        taskEntity.setDataDate(LocalDate.of(2026, 4, 3));
        taskEntity.setRawDataLastTime(LocalDateTime.of(2026, 4, 3, 3, 10));
        taskEntity.setTriggerReasonCodes(triggerReasonCodes);
        taskEntity.setChangedTypes(changedTypes);
        return taskEntity;
    }

    private PatientRawDataEntity buildRawData(InfectionEventTaskEntity taskEntity) {
        PatientRawDataEntity rawData = new PatientRawDataEntity();
        rawData.setId(taskEntity.getPatientRawDataId());
        rawData.setReqno(taskEntity.getReqno());
        rawData.setDataDate(taskEntity.getDataDate());
        rawData.setLastTime(taskEntity.getRawDataLastTime());
        rawData.setDataJson("{\"data\":true}");
        rawData.setFilterDataJson("{\"filtered\":true}");
        return rawData;
    }

    private EvidenceBlockBuildResult buildResultWithAllPrimaryBlocks() {
        LocalDate dataDate = LocalDate.of(2026, 4, 3);
        EvidenceBlock structured = new EvidenceBlock(
                "block-structured",
                "REQ-5001",
                201L,
                dataDate,
                EvidenceBlockType.STRUCTURED_FACT,
                InfectionSourceType.RAW,
                "filter_data_json",
                "structured",
                "{\"data\":{\"lab_results\":{}}}",
                false
        );
        EvidenceBlock clinical = new EvidenceBlock(
                "block-clinical",
                "REQ-5001",
                201L,
                dataDate,
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-1",
                "病程",
                "{\"note_text\":\"考虑感染\"}",
                false
        );
        EvidenceBlock semantic = new EvidenceBlock(
                "block-semantic",
                "REQ-5001",
                201L,
                dataDate,
                EvidenceBlockType.MID_SEMANTIC,
                InfectionSourceType.MID,
                "event_json",
                "语义",
                "{\"risk\":[\"近期手术\"]}",
                false
        );
        EvidenceBlock timeline = new EvidenceBlock(
                "block-timeline",
                "REQ-5001",
                201L,
                dataDate,
                EvidenceBlockType.TIMELINE_CONTEXT,
                InfectionSourceType.SUMMARY,
                "summary_json",
                "时间线",
                "{\"changes\":[]}",
                true
        );
        return new EvidenceBlockBuildResult(List.of(structured), List.of(clinical), List.of(semantic), List.of(timeline));
    }
}
