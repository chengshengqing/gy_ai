package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionCertainty;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventType;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionPolarity;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventNormalizerServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EventNormalizerServiceImpl eventNormalizerService;

    @BeforeEach
    void setUp() {
        eventNormalizerService = new EventNormalizerServiceImpl(objectMapper);
    }

    @Test
    void normalizeMapsExtractorOutputIntoStableNormalizedEvents() throws Exception {
        EvidenceBlock block = new EvidenceBlock(
                "block-1",
                "REQ-9001",
                321L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-1",
                "日常病程记录",
                "{\"note\":\"今日发热\"}",
                false
        );

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(block, """
                {
                  "events": [
                    {
                      "event_time": "2026-03-30 09:25:55",
                      "event_type": "assessment",
                      "event_subtype": "contamination_possible",
                      "body_site": "urinary",
                      "event_name": "考虑污染",
                      "abnormal_flag": null,
                      "infection_related": true,
                      "negation_flag": false,
                      "uncertainty_flag": true,
                      "clinical_meaning": "infection_uncertain",
                      "source_text": "患者自诉未取中段尿，考虑污染所致"
                    }
                  ]
                }
                """, InfectionExtractorType.LLM_EVENT_EXTRACTOR, "v1", "gpt-test", new BigDecimal("0.82"));

        assertEquals(1, events.size());
        NormalizedInfectionEvent event = events.get(0);
        assertEquals("REQ-9001", event.getReqno());
        assertEquals(InfectionEventType.ASSESSMENT.code(), event.getEventType());
        assertEquals("contamination_possible", event.getEventSubtype());
        assertEquals("urinary", event.getSite());
        assertEquals(InfectionPolarity.NEUTRAL.code(), event.getPolarity());
        assertEquals(InfectionCertainty.POSSIBLE.code(), event.getCertainty());
        assertEquals(InfectionEventStatus.ACTIVE.code(), event.getStatus());
        assertEquals("raw", event.getSourceType());
        assertTrue(event.getEventKey().startsWith("REQ-9001|2026-03-30|raw|pat_illnessCourse|"));

        JsonNode attributes = objectMapper.readTree(event.getAttributesJson());
        assertEquals("infection_uncertain", attributes.path("clinical_meaning").asText());
        JsonNode evidence = objectMapper.readTree(event.getEvidenceJson());
        assertEquals("block-1", evidence.path("block_key").asText());
        assertTrue(evidence.path("source_text").asText().contains("考虑污染"));
    }

    @Test
    void normalizeInfersEventTypeForStructuredFactAndSkipsContextBlocks() {
        EvidenceBlock structuredBlock = new EvidenceBlock(
                "block-2",
                "REQ-9002",
                322L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.STRUCTURED_FACT,
                InfectionSourceType.RAW,
                "filter_data_json.lab_results",
                "lab_results",
                "{}",
                false
        );
        List<NormalizedInfectionEvent> factEvents = eventNormalizerService.normalize(structuredBlock, """
                {
                  "events": [
                    {
                      "event_time": "2026-03-30",
                      "event_type": "",
                      "event_subtype": "lab_abnormal",
                      "body_site": "unknown",
                      "event_name": "WBC升高",
                      "infection_related": true,
                      "negation_flag": false,
                      "uncertainty_flag": false,
                      "source_text": "WBC 14.8"
                    }
                  ]
                }
                """, InfectionExtractorType.LLM_EVENT_EXTRACTOR, "v1", "gpt-test", new BigDecimal("0.95"));
        assertEquals(1, factEvents.size());
        assertEquals(InfectionEventType.LAB_RESULT.code(), factEvents.get(0).getEventType());
        assertEquals(Boolean.TRUE, factEvents.get(0).getIsHardFact());

        EvidenceBlock contextBlock = new EvidenceBlock(
                "block-3",
                "REQ-9003",
                323L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.TIMELINE_CONTEXT,
                InfectionSourceType.SUMMARY,
                "summary_json",
                "timeline_context",
                "{}",
                true
        );
        List<NormalizedInfectionEvent> contextEvents = eventNormalizerService.normalize(contextBlock, """
                {"events":[{"event_type":"note"}]}
                """, InfectionExtractorType.LLM_EVENT_EXTRACTOR, "v1", "gpt-test", new BigDecimal("0.95"));
        assertTrue(contextEvents.isEmpty());
    }
}
