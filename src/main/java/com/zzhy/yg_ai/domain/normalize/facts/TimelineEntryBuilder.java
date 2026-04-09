package com.zzhy.yg_ai.domain.normalize.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TimelineEntryBuilder {

    public List<Map<String, Object>> buildDailyFusionTimelineEntries(PatientRawDataEntity rawData, JsonNode dailyFusionNode) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (dailyFusionNode == null || !dailyFusionNode.isObject()) {
            return entries;
        }
        Map<String, Object> timelineEntry = new LinkedHashMap<>();
        dailyFusionNode.fields().forEachRemaining(entry -> timelineEntry.put(entry.getKey(), entry.getValue()));
        timelineEntry.put("time", rawData == null || rawData.getDataDate() == null ? "" : rawData.getDataDate().toString());
        timelineEntry.put("record_type", "daily_fusion");
        timelineEntry.put("day_summary", dailyFusionNode.path("day_summary").asText(""));
        entries.add(timelineEntry);
        return entries;
    }
}
