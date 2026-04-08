package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
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
    void normalizeAcceptsValidClinicalTextEvent() throws Exception {
        EvidenceBlock block = new EvidenceBlock(
                "block-1",
                "REQ-9001",
                321L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-1",
                "日常病程记录",
                """
                {
                  "note_text":"患者发热咳嗽，考虑呼吸道感染，建议继续观察。"
                }
                """,
                false
        );

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(block, """
                {
                  "status": "success",
                  "confidence": 0.82,
                  "events": [
                    {
                      "event_time": "2026-03-30 09:25:55",
                      "event_type": "assessment",
                      "event_subtype": "infection_positive_statement",
                      "body_site": "respiratory",
                      "event_name": "考虑呼吸道感染",
                      "event_value": null,
                      "event_unit": null,
                      "abnormal_flag": null,
                      "infection_related": true,
                      "negation_flag": false,
                      "uncertainty_flag": false,
                      "clinical_meaning": "infection_support",
                      "source_section": null,
                      "source_text": "考虑呼吸道感染",
                      "evidence_tier": "moderate",
                      "evidence_role": "support"
                    }
                  ]
                }
                """, InfectionExtractorType.LLM_EVENT_EXTRACTOR, "v1", "gpt-test", new BigDecimal("0.82"));

        assertEquals(1, events.size());
        NormalizedInfectionEvent event = events.get(0);
        assertEquals("REQ-9001", event.getReqno());
        assertEquals(InfectionEventType.ASSESSMENT.code(), event.getEventType());
        assertEquals("infection_positive_statement", event.getEventSubtype());
        assertEquals("respiratory", event.getSite());
        assertEquals(InfectionPolarity.POSITIVE.code(), event.getPolarity());
        assertEquals(InfectionEventStatus.ACTIVE.code(), event.getStatus());

        JsonNode attributes = objectMapper.readTree(event.getAttributesJson());
        assertEquals("infection_support", attributes.path("clinical_meaning").asText());
        assertEquals("support", attributes.path("evidence_role").asText());
    }

    @Test
    void normalizeThrowsWhenExtractorOutputIsInvalidJson() {
        EvidenceBlock block = buildClinicalTextBlock("{\"note_text\":\"考虑呼吸道感染\"}");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                eventNormalizerService.normalize(
                        block,
                        "{\"status\":\"success\"",
                        InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                        "v1",
                        "gpt-test",
                        new BigDecimal("0.80")
                ));

        assertEquals("EventNormalizer failed to parse extractor output JSON", exception.getMessage());
    }

    @Test
    void normalizeThrowsWhenSuccessResponseHasEmptyEvents() {
        EvidenceBlock block = buildClinicalTextBlock("{\"note_text\":\"考虑呼吸道感染\"}");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                eventNormalizerService.normalize(
                        block,
                        """
                        {
                          "status": "success",
                          "confidence": 0.81,
                          "events": []
                        }
                        """,
                        InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                        "v1",
                        "gpt-test",
                        new BigDecimal("0.81")
                ));

        assertEquals("EventNormalizer success response must contain events", exception.getMessage());
    }

    @Test
    void normalizeThrowsWhenAllEventsAreRejected() {
        EvidenceBlock block = buildClinicalTextBlock("""
                {
                  "note_text":"患者发热咳嗽，考虑呼吸道感染，建议继续观察。"
                }
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                eventNormalizerService.normalize(
                        block,
                        """
                        {
                          "status": "success",
                          "confidence": 0.76,
                          "events": [
                            {
                              "event_time": "2026-03-30 09:25:55",
                              "event_type": "assessment",
                              "event_subtype": "infection_positive_statement",
                              "body_site": "respiratory",
                              "event_name": "考虑呼吸道感染",
                              "event_value": null,
                              "event_unit": null,
                              "abnormal_flag": null,
                              "infection_related": true,
                              "negation_flag": false,
                              "uncertainty_flag": false,
                              "clinical_meaning": "infection_support",
                              "source_section": null,
                              "source_text": "与当前文本无关的来源句子",
                              "evidence_tier": "moderate",
                              "evidence_role": "support"
                            }
                          ]
                        }
                        """,
                        InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                        "v1",
                        "gpt-test",
                        new BigDecimal("0.76")
                ));

        assertEquals("EventNormalizer rejected all extracted events", exception.getMessage());
    }

    private EvidenceBlock buildClinicalTextBlock(String payloadJson) {
        return new EvidenceBlock(
                "block-1",
                "REQ-9001",
                321L,
                LocalDate.of(2026, 3, 30),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.N-1",
                "日常病程记录",
                payloadJson,
                false
        );
    }
}
