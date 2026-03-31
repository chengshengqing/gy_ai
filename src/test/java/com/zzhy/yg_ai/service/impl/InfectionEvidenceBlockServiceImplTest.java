package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.service.evidence.ClinicalTextBlockBuilder;
import com.zzhy.yg_ai.service.evidence.MidSemanticBlockBuilder;
import com.zzhy.yg_ai.service.evidence.StructuredFactBlockBuilder;
import com.zzhy.yg_ai.service.evidence.TimelineContextBlockBuilder;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InfectionEvidenceBlockServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InfectionEvidenceBlockServiceImpl infectionEvidenceBlockService;

    @BeforeEach
    void setUp() {
        infectionEvidenceBlockService = new InfectionEvidenceBlockServiceImpl(
                new StructuredFactBlockBuilder(objectMapper),
                new ClinicalTextBlockBuilder(objectMapper),
                new MidSemanticBlockBuilder(objectMapper),
                new TimelineContextBlockBuilder(objectMapper)
        );
    }

    @Test
    void buildBlocksSplitsAllFourEvidenceTypes() throws Exception {
        PatientRawDataEntity rawData = new PatientRawDataEntity();
        rawData.setId(101L);
        rawData.setReqno("REQ-7001");
        rawData.setDataDate(LocalDate.of(2026, 3, 28));
        rawData.setFilterDataJson("""
                {
                  "patient_info": {"name": "张三"},
                  "diagnosis": ["肺部感染", "高血压"],
                  "lab_results": {
                    "abnormal": ["WBC 14.2", "CRP 86"]
                  },
                  "doctor_orders": {
                    "temporary": ["痰培养", "头孢哌酮舒巴坦"]
                  },
                  "pat_illnessCourse": [
                    {
                      "illnessCourseId": "N-1",
                      "itemname": "日常病程记录",
                      "creattime": "2026-03-28 08:30:00",
                      "illnesscontent": "今日发热，考虑肺部感染，已送痰培养。"
                    },
                    {
                      "illnessCourseId": "N-2",
                      "itemname": "会诊记录",
                      "creattime": "2026-03-28 10:00:00",
                      "illnesscontent": "建议继续观察感染指标变化，排除导管相关感染。"
                    }
                  ]
                }
                """);
        rawData.setStructDataJson("""
                {
                  "core_problems": [
                    {"problem": "疑似肺部感染", "status": "active"}
                  ],
                  "differential_diagnosis": [
                    {"diagnosis": "导管相关感染"}
                  ],
                  "risk_alerts": [
                    {"risk": "长期留置导尿增加感染风险"}
                  ]
                }
                """);
        PatientSummaryEntity summary = new PatientSummaryEntity();
        summary.setSummaryJson("""
                {
                  "timeline": [
                    {
                      "time": "2026-03-27",
                      "day_summary": "前一日体温正常"
                    },
                    {
                      "time": "2026-03-28",
                      "day_summary": "新发热并开始感染评估"
                    }
                  ]
                }
                """);

        EvidenceBlockBuildResult result = infectionEvidenceBlockService.buildBlocks(rawData, summary);

        assertEquals(3, result.structuredFactBlocks().size());
        assertEquals(2, result.clinicalTextBlocks().size());
        assertEquals(3, result.midSemanticBlocks().size());
        assertEquals(1, result.timelineContextBlocks().size());
        assertEquals(9, result.allBlocks().size());
        assertEquals(8, result.primaryBlocks().size());

        EvidenceBlock structuredLabBlock = result.structuredFactBlocks().stream()
                .filter(block -> "filter_data_json.lab_results".equals(block.sourceRef()))
                .findFirst()
                .orElseThrow();
        JsonNode structuredPayload = objectMapper.readTree(structuredLabBlock.payloadJson());
        assertEquals(EvidenceBlockType.STRUCTURED_FACT, structuredLabBlock.blockType());
        assertEquals("lab_results", structuredPayload.path("section").asText());
        assertEquals("WBC 14.2", structuredPayload.path("data").path("abnormal").get(0).asText());

        EvidenceBlock clinicalBlock = result.clinicalTextBlocks().get(0);
        JsonNode clinicalPayload = objectMapper.readTree(clinicalBlock.payloadJson());
        assertEquals(EvidenceBlockType.CLINICAL_TEXT, clinicalBlock.blockType());
        assertEquals("N-1", clinicalPayload.path("note_id").asText());
        assertTrue(clinicalPayload.path("note_text").asText().contains("今日发热"));

        EvidenceBlock riskBlock = result.midSemanticBlocks().stream()
                .filter(block -> "struct_data_json.risk".equals(block.sourceRef()))
                .findFirst()
                .orElseThrow();
        JsonNode riskPayload = objectMapper.readTree(riskBlock.payloadJson());
        assertEquals("risk", riskPayload.path("section").asText());
        assertEquals("长期留置导尿增加感染风险", riskPayload.path("data").get(0).path("risk").asText());

        EvidenceBlock timelineBlock = result.timelineContextBlocks().get(0);
        JsonNode timelinePayload = objectMapper.readTree(timelineBlock.payloadJson());
        assertTrue(timelineBlock.contextOnly());
        assertEquals("summary_json", timelineBlock.sourceRef());
        assertEquals("新发热并开始感染评估", timelinePayload.path("data").path("timeline").get(1).path("day_summary").asText());
    }

    @Test
    void buildBlocksFallsBackToRawDataNotesAndSkipsEmptyPayloads() {
        PatientRawDataEntity rawData = new PatientRawDataEntity();
        rawData.setId(202L);
        rawData.setReqno("REQ-7002");
        rawData.setDataDate(LocalDate.of(2026, 3, 29));
        rawData.setFilterDataJson("""
                {
                  "patient_info": {"name": "李四"},
                  "diagnosis": []
                }
                """);
        rawData.setDataJson("""
                {
                  "pat_illnessCourse": [
                    {
                      "itemname": "查房记录",
                      "creattime": "2026-03-29 09:00:00",
                      "illnesscontent": "暂无明确感染证据。"
                    }
                  ]
                }
                """);
        rawData.setStructDataJson("""
                {
                  "core_problems": [],
                  "risk_flags": []
                }
                """);

        EvidenceBlockBuildResult result = infectionEvidenceBlockService.buildBlocks(rawData, null);

        assertTrue(result.structuredFactBlocks().isEmpty());
        assertEquals(1, result.clinicalTextBlocks().size());
        assertTrue(result.midSemanticBlocks().isEmpty());
        assertTrue(result.timelineContextBlocks().isEmpty());
        assertFalse(result.isEmpty());
        List<EvidenceBlock> allBlocks = result.allBlocks();
        assertEquals(1, allBlocks.size());
        assertTrue(allBlocks.get(0).sourceRef().startsWith("pat_illnessCourse."));
    }
}
