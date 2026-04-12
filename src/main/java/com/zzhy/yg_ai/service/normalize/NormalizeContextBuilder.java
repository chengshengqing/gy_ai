package com.zzhy.yg_ai.service.normalize;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import com.zzhy.yg_ai.domain.normalize.assemble.DailyFusionPlan;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeContext;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeNoteStructureResult;
import com.zzhy.yg_ai.domain.normalize.assemble.NotePromptTask;
import com.zzhy.yg_ai.domain.normalize.facts.DayFactsBuilder;
import com.zzhy.yg_ai.domain.normalize.facts.FusionFactsBuilder;
import com.zzhy.yg_ai.domain.normalize.facts.support.DailyFusionInputCompactor;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptCatalog;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NormalizeContextBuilder {

    private static final String EMPTY_JSON = "{}";
    private static final DateTimeFormatter ILLNESS_TIME_FORMATTER = DateTimeUtils.DATE_TIME_FORMATTER;

    private final ObjectMapper objectMapper;
    private final NormalizePromptCatalog promptCatalog;
    private final DayFactsBuilder dayFactsBuilder;
    private final FusionFactsBuilder fusionFactsBuilder;
    private final DailyFusionInputCompactor dailyFusionInputCompactor;

    public NormalizeContext buildContext(PatientRawDataEntity rawData) {
        if (rawData == null) {
            return new NormalizeContext("", null, objectMapper.createObjectNode(), false);
        }
        String rawInputJson = resolveRawInputJson(rawData);
        return new NormalizeContext(
                defaultIfBlank(rawData.getReqno(), ""),
                rawData.getDataDate(),
                parseToNode(rawInputJson),
                StringUtils.hasText(rawInputJson)
        );
    }

    public List<NotePromptTask> buildNoteTasks(NormalizeContext context) {
        if (context == null || context.rawRoot() == null) {
            return List.of();
        }
        List<PatientCourseData.PatIllnessCourse> illnessCourseList = objectMapper.convertValue(
                context.rawRoot().path("pat_illnessCourse"),
                new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {
                });
        if (illnessCourseList == null || illnessCourseList.isEmpty()) {
            return List.of();
        }

        List<NotePromptTask> tasks = new ArrayList<>();
        for (PatientCourseData.PatIllnessCourse illnessCourse : illnessCourseList) {
            if (illnessCourse == null || !StringUtils.hasText(illnessCourse.getIllnesscontent())) {
                continue;
            }
            String noteType = illnessCourse.getItemname();
            String noteTime = resolveNoteTime(illnessCourse, context.dataDate());
            NormalizePromptDefinition promptDefinition = promptCatalog.selectIllnessPrompt(noteType);
            Map<String, Object> currentIllness = new LinkedHashMap<>();
            currentIllness.put("itemname", noteType);
            currentIllness.put("time", noteTime);
            currentIllness.put("illnesscontent", illnessCourse.getIllnesscontent());
            tasks.add(new NotePromptTask(
                    noteType,
                    noteTime,
                    resolveNotePriority(noteType),
                    promptDefinition,
                    toJson(currentIllness)
            ));
        }
        tasks.sort(Comparator
                .comparingInt(NotePromptTask::sortPriority)
                .thenComparing(task -> defaultIfBlank(task.noteTime(), ""))
                .thenComparing(task -> defaultIfBlank(task.noteType(), "")));
        return tasks;
    }

    public Map<String, Object> buildDayContext(NormalizeContext context, NormalizeNoteStructureResult noteResult) {
        JsonNode rawRoot = context == null || context.rawRoot() == null ? objectMapper.createObjectNode() : context.rawRoot();
        List<Map<String, Object>> noteStructuredList = noteResult == null ? List.of() : noteResult.noteStructuredList();
        return dayFactsBuilder.buildStandardizedDayFacts(rawRoot, noteStructuredList);
    }

    public DailyFusionPlan buildDailyFusionPlan(NormalizeContext context, Map<String, Object> dayContext) {
        if (!fusionFactsBuilder.canGenerateDailyFusion(dayContext)) {
            return new DailyFusionPlan(false, "daily_fusion skipped", promptCatalog.dailyFusionPrompt(), "");
        }
        String reqno = context == null ? "" : context.reqno();
        LocalDate dataDate = context == null ? null : context.dataDate();
        Map<String, Object> fusionReadyFactsInput = fusionFactsBuilder.buildFusionReadyFacts(dayContext, reqno, dataDate);
        DailyFusionInputCompactor.CompactionResult compactionResult = dailyFusionInputCompactor.compactIfNeeded(
                reqno,
                dataDate,
                fusionReadyFactsInput
        );
        String inputJson = compactionResult.inputJson();
        return new DailyFusionPlan(true, "", promptCatalog.dailyFusionPrompt(), inputJson);
    }

    private String resolveRawInputJson(PatientRawDataEntity rawData) {
        return StringUtils.hasText(rawData.getFilterDataJson()) ? rawData.getFilterDataJson() : rawData.getDataJson();
    }

    private JsonNode parseToNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String resolveNoteTime(PatientCourseData.PatIllnessCourse illnessCourse, LocalDate dataDate) {
        LocalDateTime dt = illnessCourse.getChangetime();
        if (dt == null) {
            dt = illnessCourse.getCreattime();
        }
        if (dt == null && dataDate != null) {
            dt = dataDate.atTime(LocalTime.MIN);
        }
        return dt == null ? "" : DateTimeUtils.truncateToMillis(dt).format(ILLNESS_TIME_FORMATTER);
    }

    private int resolveNotePriority(String noteType) {
        return switch (IllnessRecordType.resolve(noteType)) {
            case FIRST_COURSE, ADMISSION -> 1;
            case SURGERY -> 2;
            case CONSULTATION -> 3;
            case DAILY -> 4;
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return EMPTY_JSON;
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
