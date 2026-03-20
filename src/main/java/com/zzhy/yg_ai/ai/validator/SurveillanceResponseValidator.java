package com.zzhy.yg_ai.ai.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzhy.yg_ai.common.JsonUtil;

import java.util.ArrayList;
import java.util.List;


/**
 * 症候群监测模型返回内容的校验器
 * 校验规则依据 SURVEILLANCE_PROMPT 中的定义，所有校验必须全部通过
 */
public class SurveillanceResponseValidator {

    /**
     * 校验模型返回的 content
     *
     * @param content 模型返回的原始字符串
     * @return 校验结果，包含是否通过及失败原因列表
     */
    public static ValidationResult validate(String content) {
        List<String> failureReasons = new ArrayList<>();

        // 1. 校验是否为 JSON 格式
        if (content == null || content.trim().isEmpty()) {
            failureReasons.add("返回内容为空");
            return new ValidationResult(false, failureReasons);
        }
        if (!JsonUtil.isValidJson(content)) {
            failureReasons.add("返回内容不是有效的 JSON 格式");
            return new ValidationResult(false, failureReasons);
        }

        JsonNode root;
        try {
            root = JsonUtil.toJsonNode(content);
        } catch (JsonUtil.JsonException e) {
            failureReasons.add("JSON 解析失败：" + e.getMessage());
            return new ValidationResult(false, failureReasons);
        }

        if (!root.isObject()) {
            failureReasons.add("JSON 根节点必须为对象类型");
            return new ValidationResult(false, failureReasons);
        }

        // 2. 校验 riskLevel
        if (!root.has(SurveillanceValidationEnums.RISK_LEVEL)) {
            failureReasons.add("缺少必填字段：riskLevel");
        } else {
            JsonNode riskLevelNode = root.get(SurveillanceValidationEnums.RISK_LEVEL);
            if (riskLevelNode == null || riskLevelNode.isNull()) {
                failureReasons.add("riskLevel 不能为空");
            } else if (!riskLevelNode.isTextual()) {
                failureReasons.add("riskLevel 必须为字符串类型");
            } else {
                String value = riskLevelNode.asText();
                if (!SurveillanceValidationEnums.RiskLevel.isValid(value)) {
                    failureReasons.add("riskLevel 值不合法：" + value +
                            "，允许的值：无风险、低风险、中风险、高风险");
                }
            }
        }

        // 3. 校验 syndromeType
        if (!root.has(SurveillanceValidationEnums.SYNDROME_TYPE)) {
            failureReasons.add("缺少必填字段：syndromeType");
        } else {
            JsonNode syndromeTypeNode = root.get(SurveillanceValidationEnums.SYNDROME_TYPE);
            if (syndromeTypeNode == null || syndromeTypeNode.isNull()) {
                failureReasons.add("syndromeType 不能为空");
            } else if (!syndromeTypeNode.isTextual()) {
                failureReasons.add("syndromeType 必须为字符串类型");
            } else {
                String value = syndromeTypeNode.asText();
                if (!SurveillanceValidationEnums.SyndromeType.isValid(value)) {
                    failureReasons.add("syndromeType 值不合法：" + value +
                            "，允许的值：发热呼吸道症候群、腹泻症候群、发热伴出疹症候群、" +
                            "发热伴出血症候群、脑炎脑膜炎症候群、其他重要症候群、无");
                }
            }
        }

        // 4. 校验 keyEvidence
        /*if (!root.has(KEY_EVIDENCE)) {
            failureReasons.add("缺少必填字段：keyEvidence");
        } else {
            failureReasons.addAll(SurveillanceValidationEnums.validateKeyEvidence(root.get(KEY_EVIDENCE)));
        }*/

        // 5. 校验 analysisReasoning
        if (!root.has(SurveillanceValidationEnums.ANALYSIS_REASONING)) {
            failureReasons.add("缺少必填字段：analysisReasoning");
        } else {
            failureReasons.addAll(SurveillanceValidationEnums.validateAnalysisReasoning(root.get(SurveillanceValidationEnums.ANALYSIS_REASONING)));
        }

        // 6. 校验 recommendedActions
        /*if (!root.has(RECOMMENDED_ACTIONS)) {
            failureReasons.add("缺少必填字段：recommendedActions");
        } else {
            failureReasons.addAll(SurveillanceValidationEnums.validateRecommendedActions(root.get(RECOMMENDED_ACTIONS)));
        }*/

        boolean passed = failureReasons.isEmpty();
        return new ValidationResult(passed, failureReasons);
    }

    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final boolean passed;
        private final List<String> failureReasons;

        public ValidationResult(boolean passed, List<String> failureReasons) {
            this.passed = passed;
            this.failureReasons = failureReasons != null ? failureReasons : List.of();
        }

        public boolean isPassed() {
            return passed;
        }

        public List<String> getFailureReasons() {
            return failureReasons;
        }

        /**
         * 获取校验失败原因的拼接字符串
         */
        public String getFailureReasonMessage() {
            return String.join("；", failureReasons);
        }
    }
}
