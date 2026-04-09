package com.zzhy.yg_ai.pipeline.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeNoteStructAssembler;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeNoteStructureResult;
import com.zzhy.yg_ai.domain.normalize.facts.DailyIllnessExtractionResult;
import com.zzhy.yg_ai.domain.normalize.facts.DayFactsBuilder;
import com.zzhy.yg_ai.domain.normalize.facts.FusionFactsBuilder;
import com.zzhy.yg_ai.domain.normalize.facts.TimelineEntryBuilder;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptCatalog;
import com.zzhy.yg_ai.domain.normalize.validation.NormalizeOutputValidator;
import com.zzhy.yg_ai.domain.normalize.validation.NormalizeValidatedResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NormalizeStructDataComposer {

    private static final String EMPTY_STRUCT_DATA_JSON = "{}";

    private final ObjectMapper objectMapper;
    private final NormalizePromptCatalog promptCatalog;
    private final NormalizeNoteStructAssembler normalizeNoteStructAssembler;
    private final DayFactsBuilder dayFactsBuilder;
    private final FusionFactsBuilder fusionFactsBuilder;
    private final TimelineEntryBuilder timelineEntryBuilder;
    private final NormalizeOutputValidator normalizeOutputValidator;

    public DailyIllnessExtractionResult compose(PatientRawDataEntity rawData) {
        if (rawData == null) {
            return new DailyIllnessExtractionResult(EMPTY_STRUCT_DATA_JSON, null);
        }

        String rawInputJson = resolveRawInputJson(rawData);
        if (!StringUtils.hasText(rawInputJson)) {
            return new DailyIllnessExtractionResult(EMPTY_STRUCT_DATA_JSON, null);
        }

        JsonNode root = parseToNode(rawInputJson);
        NormalizeNoteStructureResult noteStructure = normalizeNoteStructAssembler.assemble(root, rawData);
        Map<String, Object> dayContext = dayFactsBuilder.buildStandardizedDayFacts(root, noteStructure.noteStructuredList());
        NormalizeValidatedResult dailyFusionResult = validateDailyFusion(dayContext, rawData);

        Map<String, Object> structData = new LinkedHashMap<>();
        structData.put("day_context", dayContext);
        structData.put("validation", Map.of(
                "notes", noteStructure.noteValidationList(),
                "daily_fusion", dailyFusionResult.toReport()
        ));

        String structDataJson = toJson(structData);
        String dailySummaryJson = buildDailySummaryJson(rawData, dailyFusionResult.outputNode());
        return new DailyIllnessExtractionResult(structDataJson, dailySummaryJson);
    }

    private NormalizeValidatedResult validateDailyFusion(Map<String, Object> dayContext, PatientRawDataEntity rawData) {
        if (!fusionFactsBuilder.canGenerateDailyFusion(dayContext)) {
            return new NormalizeValidatedResult(
                    false,
                    objectMapper.createObjectNode(),
                    List.of(),
                    "daily_fusion skipped"
            );
        }

        Map<String, Object> fusionReadyFactsInput = fusionFactsBuilder.buildFusionReadyFacts(dayContext, rawData);
        String userInput = toJson(Map.of("fusion_ready_facts", objectMapper.valueToTree(fusionReadyFactsInput)));
        dayContext.put("llm_input", userInput);
        return normalizeOutputValidator.validateWithRetry(promptCatalog.dailyFusionPrompt(), userInput);
    }

    private String buildDailySummaryJson(PatientRawDataEntity rawData, JsonNode validatedDailyFusionNode) {
        List<Map<String, Object>> timelineAppendEntries = validatedDailyFusionNode != null
                && validatedDailyFusionNode.isObject()
                && validatedDailyFusionNode.size() > 0
                ? timelineEntryBuilder.buildDailyFusionTimelineEntries(rawData, validatedDailyFusionNode)
                : Collections.emptyList();
        return timelineAppendEntries.isEmpty() ? null : toJson(timelineAppendEntries.get(0));
    }

    private String resolveRawInputJson(PatientRawDataEntity rawData) {
        return StringUtils.hasText(rawData.getFilterDataJson()) ? rawData.getFilterDataJson() : rawData.getDataJson();
    }

    private JsonNode parseToNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return EMPTY_STRUCT_DATA_JSON;
        }
    }
}
