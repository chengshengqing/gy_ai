package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.agent.WarningAgent;
import com.zzhy.yg_ai.domain.entity.InfectionLlmNodeRunEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.service.InfectionLlmNodeRunService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StructuredFactRefinementServiceImplTest {

    @Mock
    private WarningAgent warningAgent;
    @Mock
    private InfectionLlmNodeRunService infectionLlmNodeRunService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StructuredFactRefinementServiceImpl structuredFactRefinementService;

    @BeforeEach
    void setUp() {
        structuredFactRefinementService = new StructuredFactRefinementServiceImpl(
                warningAgent,
                infectionLlmNodeRunService,
                objectMapper
        );
    }

    @Test
    void refineWritesNormalizedOutputStats() throws Exception {
        when(infectionLlmNodeRunService.createPendingRun(any())).thenAnswer(invocation -> {
            InfectionLlmNodeRunEntity entity = invocation.getArgument(0);
            entity.setId(2001L);
            return entity;
        });
        when(warningAgent.callStructuredFactRefinement(any(), any())).thenReturn("""
                {
                  "items": [
                    {
                      "source_section": "doctor_orders",
                      "candidate_id": "doctor_orders:c1",
                      "promotion": "promote"
                    }
                  ]
                }
                """);

        EvidenceBlock structuredBlock = new EvidenceBlock(
                "block-structured",
                "REQ-1",
                11L,
                LocalDate.of(2026, 4, 7),
                EvidenceBlockType.STRUCTURED_FACT,
                InfectionSourceType.RAW,
                "filter_data_json",
                "structured",
                """
                {
                  "data": {
                    "doctor_orders": {
                      "reference_facts": ["头孢噻肟抗感染"],
                      "raw": []
                    }
                  }
                }
                """,
                false
        );
        EvidenceBlock timelineBlock = new EvidenceBlock(
                "block-timeline",
                "REQ-1",
                11L,
                LocalDate.of(2026, 4, 7),
                EvidenceBlockType.TIMELINE_CONTEXT,
                InfectionSourceType.SUMMARY,
                "summary_json",
                "timeline",
                "{\"changes\":[]}",
                true
        );

        EvidenceBlock refined = structuredFactRefinementService.refine(structuredBlock, timelineBlock);

        JsonNode refinedPayload = objectMapper.readTree(refined.payloadJson());
        assertEquals("头孢噻肟抗感染",
                refinedPayload.path("data").path("doctor_orders").path("priority_facts").get(0).asText());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(infectionLlmNodeRunService).markSuccess(any(), any(), payloadCaptor.capture(), any(), any());
        JsonNode root = objectMapper.readTree(payloadCaptor.getValue());
        JsonNode stats = root.path("stats");
        assertEquals(1, stats.path("raw_section_count").asInt());
        assertEquals(1, stats.path("raw_candidate_count").asInt());
        assertEquals(1, stats.path("promoted_candidate_count").asInt());
        assertEquals(0, stats.path("kept_reference_count").asInt());
        assertEquals(0, stats.path("dropped_candidate_count").asInt());
        assertEquals(1, stats.path("changed_section_count").asInt());
        assertTrue(root.path("refinements").isArray());
    }
}
