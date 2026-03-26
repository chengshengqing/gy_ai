package com.zzhy.yg_ai.agentscope.datahandler.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LongitudinalRecordProcessorTest {

    private final LongitudinalRecordProcessor processor = new LongitudinalRecordProcessor(new ObjectMapper());

    @Test
    void shouldKeepChangedAndDropTemplateInDailyCourse() throws Exception {
        PatientRawDataEntity day1 = buildRow(
                1L,
                "R001",
                LocalDate.of(2026, 3, 1),
                "首次病程记录 日常病程记录",
                """
                        {"pat_illnessCourse":[
                          "主诉右上腹痛3天。入院诊断：胆囊炎。计划抗感染治疗。",
                          "患者一般情况可。体温38.6℃。白细胞14.2x10^9/L。调整抗生素。"
                        ]}
                        """
        );

        PatientRawDataEntity day2 = buildRow(
                2L,
                "R001",
                LocalDate.of(2026, 3, 2),
                "日常病程记录",
                """
                        {"pat_illnessCourse":[
                          "患者一般情况可。体温37.2℃。白细胞10.1x10^9/L。继续观察。"
                        ]}
                        """
        );

        LongitudinalRecordProcessor.ReqnoProcessResult result = processor.processReqno(List.of(day2, day1), 0.9d);

        Assertions.assertEquals(2, result.rows().size());
        Assertions.assertTrue(result.totalRemovedSentences() >= 2);
        Assertions.assertTrue(result.timelineEvents().size() >= 2);

        String latestJson = result.rows().get(1).filteredJson();
        Assertions.assertNotNull(latestJson);
        Assertions.assertTrue(latestJson.contains("longitudinalExtract"));
        JsonNode latestNode = new ObjectMapper().readTree(latestJson);
        String filteredText = latestNode.path("longitudinalExtract")
                .path("structuredCourses")
                .path(0)
                .path("filteredText")
                .asText("");
        Assertions.assertFalse(filteredText.contains("继续观察"));
    }

    @Test
    void shouldMarkConsultAsKeyEventEvenWhenSimilar() {
        PatientRawDataEntity day1 = buildRow(
                3L,
                "R002",
                LocalDate.of(2026, 3, 3),
                "会诊记录",
                """
                        {"pat_illnessCourse":[
                          "申请会诊原因：感染控制不佳。会诊意见：建议升级抗生素。"
                        ]}
                        """
        );
        PatientRawDataEntity day2 = buildRow(
                4L,
                "R002",
                LocalDate.of(2026, 3, 4),
                "会诊记录",
                """
                        {"pat_illnessCourse":[
                          "申请会诊原因：感染控制不佳。会诊意见：建议升级抗生素。"
                        ]}
                        """
        );

        LongitudinalRecordProcessor.ReqnoProcessResult result = processor.processReqno(List.of(day1, day2), 0.95d);

        long keyEventCount = result.timelineEvents().stream()
                .filter(event -> "KEY_EVENT".equals(String.valueOf(event.get("eventLevel"))))
                .count();
        Assertions.assertTrue(keyEventCount > 0);
    }

    private PatientRawDataEntity buildRow(Long id, String reqno, LocalDate date, String clinicalNotes, String dataJson) {
        PatientRawDataEntity row = new PatientRawDataEntity();
        row.setId(id);
        row.setReqno(reqno);
        row.setDataDate(date);
        row.setClinicalNotes(clinicalNotes);
        row.setDataJson(dataJson);
        return row;
    }
}
