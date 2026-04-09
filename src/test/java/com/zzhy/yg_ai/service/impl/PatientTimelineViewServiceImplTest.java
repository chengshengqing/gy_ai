package com.zzhy.yg_ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.config.TimelineViewRuleProperties;
import com.zzhy.yg_ai.domain.dto.PatientTimelineViewData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.mapper.PatientRawDataMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatientTimelineViewServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildTimelineViewDataReadsFlatDailyFusionEventJson() {
        PatientRawDataMapper patientRawDataMapper = mock(PatientRawDataMapper.class);
        when(patientRawDataMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        PatientRawDataEntity row = new PatientRawDataEntity();
        row.setReqno("REQ-1");
        row.setDataDate(LocalDate.of(2026, 3, 3));
        row.setEventJson("""
                {
                  "time":"2026-03-03",
                  "record_type":"daily_fusion",
                  "day_summary":"2 型糖尿病血糖控制不佳，已启用胰岛素泵。",
                  "problem_list":[
                    {
                      "problem":"2 型糖尿病伴高血糖状态",
                      "problem_key":"2 型糖尿病",
                      "problem_type":"disease",
                      "priority":"high",
                      "status":"worsening",
                      "certainty":"confirmed",
                      "key_evidence":["随机血糖明显升高"],
                      "major_actions":["启动胰岛素泵"],
                      "risk_flags":["低血糖风险"],
                      "source_note_refs":["日常病程记录@2026-03-03 02:53:55.000"]
                    }
                  ],
                  "key_evidence":["随机血糖明显升高"],
                  "major_actions":["启动胰岛素泵"],
                  "risk_flags":["低血糖风险"],
                  "next_focus_24h":["监测血糖波动"],
                  "source_note_refs":["日常病程记录@2026-03-03 02:53:55.000"]
                }
                """);
        when(patientRawDataMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(row));

        PatientTimelineViewServiceImpl service = new PatientTimelineViewServiceImpl(
                patientRawDataMapper,
                objectMapper,
                new TimelineViewRuleProperties()
        );

        PatientTimelineViewData result = service.buildTimelineViewData("REQ-1", 1, 10);

        assertEquals(1, result.getItems().size());
        PatientTimelineViewData.TimelineItem item = result.getItems().get(0);
        assertEquals("2026-03-03", item.getDate());
        assertEquals("2 型糖尿病血糖控制不佳，已启用胰岛素泵。", item.getSummary());
        assertEquals("2 型糖尿病伴高血糖状态", item.getTitle());
        assertEquals("daily_fusion", item.getRawRef().getRecordType());
        assertEquals(1, item.getPrimaryProblems().size());
        assertEquals("2 型糖尿病伴高血糖状态", item.getPrimaryProblems().get(0).getName());
        assertTrue(item.getActions().contains("启动胰岛素泵"));
        assertTrue(item.getNextFocus().contains("监测血糖波动"));
    }
}
