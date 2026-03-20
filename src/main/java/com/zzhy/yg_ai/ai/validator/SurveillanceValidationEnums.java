package com.zzhy.yg_ai.ai.validator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 症候群监测模型返回结果的校验枚举及规则
 * 校验规则依据 SURVEILLANCE_PROMPT 中的定义
 */
public class SurveillanceValidationEnums {

    // 字段名称常量 - 用于从 JSON 中提取对应字段
    public static final String RISK_LEVEL = "riskLevel";
    public static final String SYNDROME_TYPE = "syndromeType";
    public static final String KEY_EVIDENCE = "keyEvidence";
    public static final String ANALYSIS_REASONING = "analysisReasoning";
    public static final String RECOMMENDED_ACTIONS = "recommendedActions";

    /**
     * 风险等级 - 可选值：无风险/低风险/中风险/高风险
     */
    public enum RiskLevel {
        NO_RISK("无风险"),
        LOW_RISK("低风险"),
        MEDIUM_RISK("中风险"),
        HIGH_RISK("高风险");

        private final String value;

        RiskLevel(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static boolean isValid(String value) {
            return Stream.of(values()).anyMatch(e -> e.value.equals(value));
        }
    }

    /**
     * 症候群类型 - 仅允许以下值
     */
    public enum SyndromeType {
        FEVER_RESPIRATORY("发热呼吸道症候群"),
        DIARRHEA("腹泻症候群"),
        FEVER_WITH_RASH("发热伴出疹症候群"),
        FEVER_WITH_HEMORRHAGE("发热伴出血症候群"),
        ENCEPHALITIS_MENINGITIS("脑炎脑膜炎症候群"),
        OTHER_IMPORTANT("其他重要症候群"),
        NONE("无");

        private final String value;

        SyndromeType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static boolean isValid(String value) {
            return Stream.of(values()).anyMatch(e -> e.value.equals(value));
        }
    }

    /**
     * 建议措施 - 例如：建议传染病筛查、建议隔离观察等
     */
    public enum RecommendedAction {
        SCREENING("建议传染病筛查"),
        ISOLATION("建议隔离观察"),
        LAB_TEST("建议实验室检测"),
        REPORT("建议上报监测系统"),
        ROUTINE("常规处理");

        private final String value;

        RecommendedAction(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static boolean isValid(String value) {
            return Stream.of(values()).anyMatch(e -> e.value.equals(value));
        }
    }

    /**
     * 校验 keyEvidence 字段：必须为字符串数组，每个元素为非空字符串
     */
    public static List<String> validateKeyEvidence(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || node.isNull()) {
            errors.add("keyEvidence 不能为空");
            return errors;
        }
        if (!node.isArray()) {
            errors.add("keyEvidence 必须为数组类型");
            return errors;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isTextual()) {
                errors.add("keyEvidence[" + i + "] 必须为字符串");
            } else if (item.asText().trim().isEmpty()) {
                errors.add("keyEvidence[" + i + "] 不能为空字符串");
            }
        }
        return errors;
    }

    /**
     * 校验 analysisReasoning 字段：必须为字符串，不超过80字
     */
    public static List<String> validateAnalysisReasoning(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || node.isNull()) {
            errors.add("analysisReasoning 不能为空");
            return errors;
        }
        if (!node.isTextual()) {
            errors.add("analysisReasoning 必须为字符串类型");
            return errors;
        }
        String text = node.asText();
        if (text.trim().isEmpty()) {
            errors.add("analysisReasoning 不能为空字符串");
        } else if (text.length() > 80) {
            errors.add("analysisReasoning 不能超过80字，当前长度：" + text.length());
        }
        return errors;
    }

    /**
     * 校验 recommendedActions 字段：必须为字符串数组，每个元素为允许的建议措施
     */
    public static List<String> validateRecommendedActions(JsonNode node) {
        List<String> errors = new ArrayList<>();
        if (node == null || node.isNull()) {
            errors.add("recommendedActions 不能为空");
            return errors;
        }
        if (!node.isArray()) {
            errors.add("recommendedActions 必须为数组类型");
            return errors;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isTextual()) {
                errors.add("recommendedActions[" + i + "] 必须为字符串");
            } else {
                String value = item.asText();
                if (!RecommendedAction.isValid(value)) {
                    errors.add("recommendedActions[" + i + "] 值不合法：" + value +
                            "，允许的值：" + String.join("、", Stream.of(RecommendedAction.values())
                            .map(RecommendedAction::getValue).toList()));
                }
            }
        }
        return errors;
    }
}
