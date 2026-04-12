package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.prompt.WarningPromptCatalog;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionExtractorType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventNormalizerServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventNormalizerServiceImpl eventNormalizerService = new EventNormalizerServiceImpl(objectMapper);

    @Test
    void normalizeDowngradesHardTierForVitalSignsStructuredFacts() throws Exception {
        EvidenceBlock block = structuredFactBlock("vital_signs", "07:40 pulse=110.0");

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(
                block,
                extractorOutput("vital_signs",
                        "vital_sign",
                        null,
                        "systemic",
                        "脉搏 110",
                        "07:40 pulse=110.0",
                        "hard",
                        "support",
                        "infection_support",
                        true,
                        false,
                        false),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        );

        assertEquals(1, events.size());
        JsonNode attributes = objectMapper.readTree(events.get(0).getAttributesJson());
        assertEquals("moderate", attributes.path("evidence_tier").asText());
    }

    @Test
    void normalizeKeepsHardTierForImagingStructuredFacts() throws Exception {
        EvidenceBlock block = structuredFactBlock("imaging", "胸片提示肺部感染");

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(
                block,
                extractorOutput("imaging",
                        "imaging",
                        null,
                        "respiratory",
                        "胸片提示肺部感染",
                        "胸片提示肺部感染",
                        "hard",
                        "support",
                        "infection_support",
                        true,
                        false,
                        false),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        );

        assertEquals(1, events.size());
        JsonNode attributes = objectMapper.readTree(events.get(0).getAttributesJson());
        assertEquals("hard", attributes.path("evidence_tier").asText());
    }

    @Test
    void normalizeAcceptsClinicalTextEventWithNullSourceSection() throws Exception {
        EvidenceBlock block = clinicalTextBlock("患者咳嗽咳痰，考虑急性下呼吸道感染，予抗感染治疗。");

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(
                block,
                extractorOutput(null,
                        "assessment",
                        "infection_positive_statement",
                        "lower_respiratory",
                        "考虑急性下呼吸道感染",
                        "考虑急性下呼吸道感染",
                        "moderate",
                        "support",
                        "infection_support",
                        true,
                        false,
                        true),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        );

        assertEquals(1, events.size());
        JsonNode attributes = objectMapper.readTree(events.get(0).getAttributesJson());
        assertTrue(attributes.path("source_section").isMissingNode());
    }

    @Test
    void normalizeTemporarilyMapsRiskOnlyClinicalMeaningForExposureEventsOnly() throws Exception {
        EvidenceBlock block = clinicalTextBlock("于16:25在超声引导下局麻后行右股静脉穿刺置管");

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(
                block,
                extractorOutput(null,
                        "procedure",
                        "procedure_exposure",
                        "vascular",
                        "右股静脉穿刺置管",
                        "于16:25在超声引导下局麻后行右股静脉穿刺置管",
                        "hard",
                        "risk_only",
                        "risk_only",
                        true,
                        false,
                        false),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        );

        assertEquals(1, events.size());
        assertEquals("risk_only", events.get(0).getCertainty());
        JsonNode attributes = objectMapper.readTree(events.get(0).getAttributesJson());
        assertEquals("baseline_problem", attributes.path("clinical_meaning").asText());
        assertEquals("risk_only", attributes.path("evidence_role").asText());
        assertEquals("risk_only_exposure_fallback", attributes.path("normalizer_fallbacks").get(0).path("reason").asText());
    }

    @Test
    void normalizeDoesNotTreatRiskOnlyAsGenericClinicalMeaningAlias() throws Exception {
        EvidenceBlock block = clinicalTextBlock("患者咳嗽咳痰，考虑急性下呼吸道感染，予抗感染治疗。");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> eventNormalizerService.normalize(
                block,
                extractorOutput(null,
                        "assessment",
                        "infection_positive_statement",
                        "lower_respiratory",
                        "考虑急性下呼吸道感染",
                        "考虑急性下呼吸道感染",
                        "moderate",
                        "support",
                        "risk_only",
                        true,
                        false,
                        true),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        ));

        assertTrue(exception.getMessage().contains("INVALID_CLINICAL_MEANING"));
    }

    @Test
    void normalizeDropsPreventiveManagementStatementWithoutTaskFailure() throws Exception {
        EvidenceBlock block = clinicalTextBlock("患者病情危重，需积极预防患者误吸、肺部感染、压疮、深静脉血栓等并发症");

        List<NormalizedInfectionEvent> events = eventNormalizerService.normalize(
                block,
                extractorOutput(null,
                        "assessment",
                        "infection_negative_statement",
                        "lower_respiratory",
                        "预防肺部感染",
                        "患者病情危重，需积极预防患者误吸、肺部感染、压疮、深静脉血栓等并发症",
                        "weak",
                        "against",
                        "screening",
                        true,
                        false,
                        false),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        );

        assertTrue(events.isEmpty());
    }

    @Test
    void normalizeRejectsClinicalTextEventWhenSourceSectionIsExplicit() throws Exception {
        EvidenceBlock block = clinicalTextBlock("患者咳嗽咳痰，考虑急性下呼吸道感染，予抗感染治疗。");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> eventNormalizerService.normalize(
                block,
                extractorOutput("diagnosis",
                        "assessment",
                        "infection_positive_statement",
                        "lower_respiratory",
                        "考虑急性下呼吸道感染",
                        "考虑急性下呼吸道感染",
                        "moderate",
                        "support",
                        "infection_support",
                        true,
                        false,
                        true),
                InfectionExtractorType.LLM_EVENT_EXTRACTOR,
                WarningPromptCatalog.EVENT_EXTRACTOR_PROMPT_VERSION,
                "warning-agent-chat-model",
                new BigDecimal("0.85")
        ));

        assertTrue(exception.getMessage().contains("NON_STRUCTURED_BLOCK_WITH_SOURCE_SECTION"));
    }

    private EvidenceBlock structuredFactBlock(String sourceSection, String sourceText) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode data = payload.putObject("data");
        ObjectNode section = data.putObject(sourceSection);
        ArrayNode priorityFacts = section.putArray("priority_facts");
        priorityFacts.add(sourceText);
        section.putArray("reference_facts");
        section.putArray("raw");
        return new EvidenceBlock(
                "block-1",
                "req-1",
                1L,
                LocalDate.of(2026, 1, 30),
                EvidenceBlockType.STRUCTURED_FACT,
                InfectionSourceType.RAW,
                "filter_data_json.structured_fact_bundle",
                "structured_fact_bundle",
                objectMapper.writeValueAsString(payload),
                false
        );
    }

    private EvidenceBlock clinicalTextBlock(String noteText) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("note_count", 1);
        payload.put("note_text", noteText);
        payload.putArray("note_ids").add("note-1");
        payload.putArray("note_types").add("病程记录");
        payload.putArray("note_times").add("2026-01-30 07:40:00");
        payload.putArray("note_refs").add("note-1");
        return new EvidenceBlock(
                "block-2",
                "req-1",
                1L,
                LocalDate.of(2026, 1, 30),
                EvidenceBlockType.CLINICAL_TEXT,
                InfectionSourceType.RAW,
                "pat_illnessCourse.note-1",
                "病程记录",
                objectMapper.writeValueAsString(payload),
                false
        );
    }

    private String extractorOutput(String sourceSection,
                                   String eventType,
                                   String eventSubtype,
                                   String bodySite,
                                   String eventName,
                                   String sourceText,
                                   String evidenceTier,
                                   String evidenceRole,
                                   String clinicalMeaning,
                                   boolean infectionRelated,
                                   boolean negationFlag,
                                   boolean uncertaintyFlag) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", "success");
        root.put("confidence", 0.85);
        ObjectNode event = root.putArray("events").addObject();
        event.put("event_time", "2026-01-30 07:40:00.000");
        event.put("event_type", eventType);
        if (eventSubtype == null) {
            event.putNull("event_subtype");
        } else {
            event.put("event_subtype", eventSubtype);
        }
        event.put("body_site", bodySite);
        event.put("event_name", eventName);
        event.put("event_value", 110.0);
        event.put("event_unit", "bpm");
        event.put("abnormal_flag", "high");
        event.put("infection_related", infectionRelated);
        event.put("negation_flag", negationFlag);
        event.put("uncertainty_flag", uncertaintyFlag);
        event.put("clinical_meaning", clinicalMeaning);
        if (sourceSection == null) {
            event.putNull("source_section");
        } else {
            event.put("source_section", sourceSection);
        }
        event.put("source_text", sourceText);
        event.put("evidence_tier", evidenceTier);
        event.put("evidence_role", evidenceRole);
        return objectMapper.writeValueAsString(root);
    }
}
