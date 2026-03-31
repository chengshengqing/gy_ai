package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.service.EventNormalizerService;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmEventExtractorServiceImplTest {

    @Mock
    private WarningAgent warningAgent;

    @Mock
    private InfectionLlmNodeRunService infectionLlmNodeRunService;

    @Mock
    private EventNormalizerService eventNormalizerService;

    @Mock
    private InfectionEventPoolService infectionEventPoolService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmEventExtractorServiceImpl llmEventExtractorService;

    @BeforeEach
    void setUp() {
        llmEventExtractorService = new LlmEventExtractorServiceImpl(
                warningAgent,
                infectionLlmNodeRunService,
                eventNormalizerService,
                infectionEventPoolService,
                objectMapper
        );
    }

    @Test
    void extractAndSaveRunsModelNormalizerAndEventPool() throws Exception {
        EvidenceBlock primaryBlock = new EvidenceBlock(
                "block-11",
                "REQ-9101",
                601L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-1",
                "病程记录",
                "{\"note_text\":\"考虑污染\"}",
                false
        );
        EvidenceBlock contextBlock = new EvidenceBlock(
                "block-12",
                "REQ-9101",
                601L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.TIMELINE_CONTEXT,
                InfectionSourceType.SUMMARY,
                "summary_json",
                "timeline_context",
                "{\"timeline\":[{\"day_summary\":\"前一日无发热\"}]}",
                true
        );
        EvidenceBlockBuildResult buildResult = new EvidenceBlockBuildResult(List.of(), List.of(primaryBlock), List.of(), List.of(contextBlock));

        when(infectionLlmNodeRunService.createPendingRun(any())).thenAnswer(invocation -> {
            InfectionLlmNodeRunEntity entity = invocation.getArgument(0);
            entity.setId(9001L);
            return entity;
        });
        when(warningAgent.callEventExtractor(any(), any())).thenReturn("""
                {
                  "status":"success",
                  "confidence":0.77,
                  "events":[
                    {
                      "event_type":"assessment",
                      "event_subtype":"contamination_possible",
                      "body_site":"urinary",
                      "event_name":"考虑污染",
                      "infection_related":true,
                      "negation_flag":false,
                      "uncertainty_flag":true,
                      "clinical_meaning":"infection_uncertain",
                      "source_text":"考虑污染"
                    }
                  ]
                }
                """);

        NormalizedInfectionEvent normalized = new NormalizedInfectionEvent();
        normalized.setReqno("REQ-9101");
        normalized.setEventKey("k-1");
        normalized.setEventType("assessment");
        when(eventNormalizerService.normalize(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(normalized));

        InfectionEventPoolEntity persisted = new InfectionEventPoolEntity();
        persisted.setId(8001L);
        persisted.setReqno("REQ-9101");
        when(infectionEventPoolService.saveNormalizedEvents(any())).thenReturn(List.of(persisted));

        LlmEventExtractorResult result = llmEventExtractorService.extractAndSave(buildResult);

        assertEquals(1, result.processedBlockCount());
        assertEquals(1, result.normalizedEvents().size());
        assertEquals(1, result.persistedEvents().size());
        JsonNode root = objectMapper.readTree(result.eventJson());
        assertEquals("k-1", root.path("events").get(0).path("eventKey").asText());

        verify(warningAgent).callEventExtractor(any(), any());
        verify(eventNormalizerService).normalize(
                any(),
                any(),
                eq(InfectionExtractorType.LLM_EVENT_EXTRACTOR),
                any(),
                any(),
                eq(new BigDecimal("0.77"))
        );
        verify(infectionEventPoolService).saveNormalizedEvents(List.of(normalized));
        verify(infectionLlmNodeRunService).markSuccess(eq(9001L), any(), any(), eq(new BigDecimal("0.77")), any());
    }

    @Test
    void extractAndSaveReturnsEmptyWhenNoPrimaryBlock() {
        EvidenceBlockBuildResult buildResult = new EvidenceBlockBuildResult(List.of(), List.of(), List.of(), List.of());

        LlmEventExtractorResult result = llmEventExtractorService.extractAndSave(buildResult);

        assertEquals(0, result.processedBlockCount());
        assertTrue(result.normalizedEvents().isEmpty());
        assertTrue(result.persistedEvents().isEmpty());
    }
}
