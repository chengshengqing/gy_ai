package com.zzhy.yg_ai.domain.normalize.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizeEnumFieldRule;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptCatalog;
import com.zzhy.yg_ai.domain.normalize.prompt.NormalizePromptDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NormalizePromptOutputValidator {

    private final ObjectMapper objectMapper;
    private final NormalizePromptCatalog promptCatalog;

    public NormalizePromptOutputValidator(ObjectMapper objectMapper, NormalizePromptCatalog promptCatalog) {
        this.objectMapper = objectMapper;
        this.promptCatalog = promptCatalog;
    }

    public NormalizeValidationResult validatePromptOutput(NormalizePromptDefinition promptDefinition, String rawOutput) {
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
        return trimmed.substring(0, Math.max(0, maxLen)) + "...";
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
