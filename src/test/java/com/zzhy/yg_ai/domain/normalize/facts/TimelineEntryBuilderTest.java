package com.zzhy.yg_ai.domain.normalize.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimelineEntryBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TimelineEntryBuilder timelineEntryBuilder = new TimelineEntryBuilder();

    @Test
    void buildDailyFusionTimelineEntriesUsesFlatRootObject() throws Exception {
        PatientRawDataEntity rawData = new PatientRawDataEntity();
        rawData.setDataDate(LocalDate.of(2026, 3, 3));
        JsonNode dailyFusionNode = objectMapper.readTree("""
                {
                  "time":"2026-01-01",
                  "record_type":"daily_fusion",
                  "day_summary":"血糖控制不佳，已启动胰岛素泵治疗。",
                  "problem_list":[{"problem":"2 型糖尿病伴高血糖状态"}],
                  "source_note_refs":["日常病程记录@2026-03-03 02:53:55.000"]
                }
                """);

        List<Map<String, Object>> entries = timelineEntryBuilder.buildDailyFusionTimelineEntries(rawData, dailyFusionNode);

        assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        assertEquals("2026-03-03", entry.get("time"));
        assertEquals("daily_fusion", entry.get("record_type"));
        assertEquals("血糖控制不佳，已启动胰岛素泵治疗。", entry.get("day_summary"));
        assertTrue(entry.containsKey("problem_list"));
        assertFalse(entry.containsKey("daily_fusion"));
    }
}
