package com.zzhy.yg_ai.domain.normalize.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.normalize.assemble.DailyFusionPlan;
import com.zzhy.yg_ai.domain.normalize.assemble.NotePromptTask;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeNoteStructureResult;
import com.zzhy.yg_ai.domain.normalize.facts.DailyIllnessExtractionResult;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizeEnumFieldRule;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptCatalog;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;
import com.zzhy.yg_ai.pipeline.scheduler.policy.PipelineStage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class NormalizeResultAssembler {

    private static final String EMPTY_JSON = "{}";

    private final ObjectMapper objectMapper;
    private final NormalizePromptCatalog promptCatalog;

    public NormalizeNoteStructureResult assembleNotes(AiGateway aiGateway, List<NotePromptTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new NormalizeNoteStructureResult(List.of(), List.of());
        }
        List<Map<String, Object>> noteStructuredList = new ArrayList<>();
        List<Map<String, Object>> noteValidationList = new ArrayList<>();
        for (NotePromptTask task : tasks) {
            NormalizeValidatedResult llmResult = validateWithRetry(aiGateway, task.promptDefinition(), task.inputJson());
            noteStructuredList.add(buildStructuredNote(task.noteType(), task.noteTime(), llmResult));
            noteValidationList.add(buildValidationNote(task.noteType(), task.noteTime(), llmResult));
        }
        return new NormalizeNoteStructureResult(noteStructuredList, noteValidationList);
    }

    public DailyIllnessExtractionResult assembleFinalResult(AiGateway aiGateway,
                                                            PatientRawDataEntity rawData,
                                                            Map<String, Object> dayContext,
                                                            NormalizeNoteStructureResult noteResult,
                                                            DailyFusionPlan fusionPlan) {
        NormalizeValidatedResult dailyFusionResult = buildDailyFusionResult(aiGateway, fusionPlan);
        Map<String, Object> structData = new LinkedHashMap<>();
        Map<String, Object> safeDayContext = dayContext == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dayContext);
        if (fusionPlan != null && fusionPlan.enabled()) {
            safeDayContext.put("llm_input", fusionPlan.inputJson());
        }
        structData.put("day_context", safeDayContext);
        structData.put("validation", Map.of(
                "notes", noteResult == null ? List.of() : noteResult.noteValidationList(),
                "daily_fusion", dailyFusionResult.toReport()
        ));
        return new DailyIllnessExtractionResult(
                toJson(structData),
                buildDailySummaryJson(rawData, dailyFusionResult.outputNode())
        );
    }

    private NormalizeValidatedResult buildDailyFusionResult(AiGateway aiGateway, DailyFusionPlan fusionPlan) {
        if (fusionPlan == null || !fusionPlan.enabled() || !StringUtils.hasText(fusionPlan.inputJson())) {
            String reason = fusionPlan == null ? "daily_fusion skipped" : defaultIfBlank(fusionPlan.skipReason(), "daily_fusion skipped");
            return new NormalizeValidatedResult(false, objectMapper.createObjectNode(), List.of(), reason);
        }
        return validateWithRetry(aiGateway, fusionPlan.promptDefinition(), fusionPlan.inputJson());
    }

    private NormalizeValidatedResult validateWithRetry(AiGateway aiGateway,
                                                       NormalizePromptDefinition promptDefinition,
                                                       String inputJson) {
        List<NormalizeAttemptReport> attempts = new ArrayList<>();
        List<NormalizeValidationIssue> issues = Collections.emptyList();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String rawOutput = callPrompt(aiGateway, promptDefinition, inputJson, issues);
            NormalizeValidationResult validationResult = validatePromptOutput(promptDefinition, rawOutput);
            attempts.add(new NormalizeAttemptReport(attempt, validationResult.valid(), validationResult.issues()));
            if (validationResult.valid()) {
                return new NormalizeValidatedResult(true, validationResult.parsedNode(), attempts, "");
            }
            issues = validationResult.issues();
        }
        String failureReason = issues.isEmpty() ? "LLM output validation failed" : issues.get(0).reason();
        log.warn("Prompt validation failed after retries, promptType={}, reason={}", promptDefinition.type(), failureReason);
        return new NormalizeValidatedResult(false, objectMapper.createObjectNode(), attempts, failureReason);
    }

    private String callPrompt(AiGateway aiGateway,
                              NormalizePromptDefinition promptDefinition,
                              String inputJson,
                              List<NormalizeValidationIssue> issues) {
        String nodeType = promptDefinition.type().name();
        if (!promptDefinition.hasUserPrompt()) {
            String userInput = issues == null || issues.isEmpty()
                    ? inputJson
                    : buildRetryUserInput(inputJson, issues);
            return aiGateway.callSystem(PipelineStage.NORMALIZE, nodeType, promptDefinition.systemPrompt(), userInput);
        }
        String userPrompt = promptDefinition.userPrompt();
        if (issues != null && !issues.isEmpty()) {
            userPrompt = userPrompt + "\n\n" + buildRetryInstruction(issues);
        }
        return aiGateway.callSystemAndUser(
                PipelineStage.NORMALIZE,
                nodeType,
                promptDefinition.systemPrompt(),
                userPrompt,
                inputJson
        );
    }

    private NormalizeValidationResult validatePromptOutput(NormalizePromptDefinition promptDefinition, String rawOutput) {
        JsonNode parsedNode = parseJsonQuietly(rawOutput);
        if (parsedNode == null) {
            return new NormalizeValidationResult(
                    false,
                    null,
                    List.of(new NormalizeValidationIssue(
                            "$",
                            buildShortExcerpt(defaultIfBlank(rawOutput, ""), 200),
                            List.of(),
                            "输出不是合法JSON"
                    ))
            );
        }
        List<NormalizeValidationIssue> issues = new ArrayList<>();
        for (NormalizeEnumFieldRule rule : promptCatalog.validationSpecFor(promptDefinition.type()).rules()) {
            validateEnumRule(rule, parsedNode, issues);
        }
        return new NormalizeValidationResult(issues.isEmpty(), parsedNode, issues);
    }

    private void validateEnumRule(NormalizeEnumFieldRule rule, JsonNode root, List<NormalizeValidationIssue> issues) {
        String[] segments = rule.jsonPath().split("\\.");
        validateEnumRuleSegments(rule, root, segments, 0, "$", issues);
    }

    private void validateEnumRuleSegments(NormalizeEnumFieldRule rule,
                                          JsonNode currentNode,
                                          String[] segments,
                                          int index,
                                          String currentPath,
                                          List<NormalizeValidationIssue> issues) {
        if (index >= segments.length) {
            validateLeafValue(rule, currentNode, currentPath, issues);
            return;
        }
        String segment = segments[index];
        boolean arraySegment = segment.endsWith("[]");
        String fieldName = arraySegment ? segment.substring(0, segment.length() - 2) : segment;
        String nextPath = currentPath + "." + fieldName;
        JsonNode nextNode = currentNode == null ? null : currentNode.get(fieldName);
        if (nextNode == null || nextNode.isMissingNode() || nextNode.isNull()) {
            if (rule.required()) {
                issues.add(new NormalizeValidationIssue(nextPath, "", new ArrayList<>(rule.allowedValues()), "字段缺失"));
            }
            return;
        }
        if (arraySegment) {
            if (!nextNode.isArray()) {
                issues.add(new NormalizeValidationIssue(
                        nextPath,
                        buildShortExcerpt(nextNode.toString(), 80),
                        new ArrayList<>(rule.allowedValues()),
                        "字段不是数组"
                ));
                return;
            }
            for (int i = 0; i < nextNode.size(); i++) {
                validateEnumRuleSegments(rule, nextNode.get(i), segments, index + 1, nextPath + "[" + i + "]", issues);
            }
            return;
        }
        validateEnumRuleSegments(rule, nextNode, segments, index + 1, nextPath, issues);
    }

    private void validateLeafValue(NormalizeEnumFieldRule rule,
                                   JsonNode valueNode,
                                   String jsonPath,
                                   List<NormalizeValidationIssue> issues) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            if (rule.required()) {
                issues.add(new NormalizeValidationIssue(jsonPath, "", new ArrayList<>(rule.allowedValues()), "字段缺失"));
            }
            return;
        }
        if (!valueNode.isTextual()) {
            issues.add(new NormalizeValidationIssue(
                    jsonPath,
                    buildShortExcerpt(valueNode.toString(), 80),
                    new ArrayList<>(rule.allowedValues()),
                    "字段必须是字符串枚举"
            ));
            return;
        }
        String value = valueNode.asText("").trim();
        if (!StringUtils.hasText(value) && rule.required()) {
            issues.add(new NormalizeValidationIssue(jsonPath, value, new ArrayList<>(rule.allowedValues()), "字段为空"));
            return;
        }
        if (StringUtils.hasText(value) && !rule.allowedValues().contains(value)) {
            issues.add(new NormalizeValidationIssue(jsonPath, value, new ArrayList<>(rule.allowedValues()), "字段值不在允许枚举中"));
        }
    }

    private String buildRetryUserInput(String inputJson, List<NormalizeValidationIssue> issues) {
        return buildRetryInstruction(issues) + "\n【输入数据】\n" + inputJson;
    }

    private String buildRetryInstruction(List<NormalizeValidationIssue> issues) {
        StringBuilder builder = new StringBuilder();
        builder.append("上一次输出未通过校验，请仅修正以下问题，并重新输出完整JSON：\n");
        for (int i = 0; i < issues.size(); i++) {
            NormalizeValidationIssue issue = issues.get(i);
            builder.append(i + 1).append(". 路径 ").append(issue.jsonPath());
            if (StringUtils.hasText(issue.invalidValue())) {
                builder.append(" 的值为 ").append(issue.invalidValue());
            }
            if (!issue.allowedValues().isEmpty()) {
                builder.append("，允许值仅为 ").append(String.join("|", issue.allowedValues()));
            }
            builder.append("。原因：").append(issue.reason()).append('\n');
        }
        builder.append("不要输出解释，只输出修正后的完整JSON。");
        return builder.toString();
    }

    private Map<String, Object> buildStructuredNote(String noteType,
                                                    String noteTime,
                                                    NormalizeValidatedResult llmResult) {
        Map<String, Object> noteStructured = new LinkedHashMap<>();
        noteStructured.put("note_type", noteType);
        noteStructured.put("timestamp", noteTime);
        noteStructured.put("structured", llmResult.success() ? llmResult.outputNode() : objectMapper.createObjectNode());
        noteStructured.put("validation", llmResult.toReport());
        return noteStructured;
    }

    private Map<String, Object> buildValidationNote(String noteType,
                                                    String noteTime,
                                                    NormalizeValidatedResult llmResult) {
        return Map.of(
                "note_type", defaultIfBlank(noteType, ""),
                "timestamp", defaultIfBlank(noteTime, ""),
                "validation", llmResult.toReport()
        );
    }

    private String buildDailySummaryJson(PatientRawDataEntity rawData, JsonNode validatedDailyFusionNode) {
        if (validatedDailyFusionNode == null
                || !validatedDailyFusionNode.isObject()
                || validatedDailyFusionNode.size() == 0) {
            return null;
        }
        Map<String, Object> timelineEntry = new LinkedHashMap<>();
        validatedDailyFusionNode.fields().forEachRemaining(entry -> timelineEntry.put(entry.getKey(), entry.getValue()));
        timelineEntry.put("time", rawData == null || rawData.getDataDate() == null ? "" : rawData.getDataDate().toString());
        timelineEntry.put("record_type", "daily_fusion");
        timelineEntry.put("day_summary", validatedDailyFusionNode.path("day_summary").asText(""));
        return toJson(timelineEntry);
    }

    private JsonNode parseJsonQuietly(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildShortExcerpt(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "...";
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
